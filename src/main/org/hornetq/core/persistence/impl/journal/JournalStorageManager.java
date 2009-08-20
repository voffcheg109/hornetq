/*
 * Copyright 2009 Red Hat, Inc.
 * Red Hat licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *    http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */

package org.hornetq.core.persistence.impl.journal;

import static org.hornetq.utils.DataConstants.SIZE_BYTE;
import static org.hornetq.utils.DataConstants.SIZE_INT;
import static org.hornetq.utils.DataConstants.SIZE_LONG;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

import javax.transaction.xa.Xid;

import org.hornetq.core.buffers.ChannelBuffers;
import org.hornetq.core.config.Configuration;
import org.hornetq.core.exception.HornetQException;
import org.hornetq.core.filter.Filter;
import org.hornetq.core.journal.EncodingSupport;
import org.hornetq.core.journal.Journal;
import org.hornetq.core.journal.PreparedTransactionInfo;
import org.hornetq.core.journal.RecordInfo;
import org.hornetq.core.journal.SequentialFile;
import org.hornetq.core.journal.SequentialFileFactory;
import org.hornetq.core.journal.impl.AIOSequentialFileFactory;
import org.hornetq.core.journal.impl.JournalImpl;
import org.hornetq.core.journal.impl.NIOSequentialFileFactory;
import org.hornetq.core.logging.Logger;
import org.hornetq.core.message.impl.MessageImpl;
import org.hornetq.core.paging.PageTransactionInfo;
import org.hornetq.core.paging.PagingManager;
import org.hornetq.core.paging.impl.PageTransactionInfoImpl;
import org.hornetq.core.persistence.QueueBindingInfo;
import org.hornetq.core.persistence.StorageManager;
import org.hornetq.core.postoffice.Binding;
import org.hornetq.core.remoting.impl.wireformat.XidCodecSupport;
import org.hornetq.core.remoting.spi.HornetQBuffer;
import org.hornetq.core.server.JournalType;
import org.hornetq.core.server.LargeServerMessage;
import org.hornetq.core.server.MessageReference;
import org.hornetq.core.server.Queue;
import org.hornetq.core.server.ServerMessage;
import org.hornetq.core.server.impl.ServerMessageImpl;
import org.hornetq.core.transaction.ResourceManager;
import org.hornetq.core.transaction.Transaction;
import org.hornetq.core.transaction.TransactionOperation;
import org.hornetq.core.transaction.TransactionPropertyIndexes;
import org.hornetq.core.transaction.Transaction.State;
import org.hornetq.core.transaction.impl.TransactionImpl;
import org.hornetq.utils.DataConstants;
import org.hornetq.utils.IDGenerator;
import org.hornetq.utils.Pair;
import org.hornetq.utils.SimpleString;
import org.hornetq.utils.UUID;

/**
 * 
 * A JournalStorageManager
 * 
 * @author <a href="mailto:tim.fox@jboss.com">Tim Fox</a>
 * @author <a href="mailto:clebert.suconic@jboss.com">Clebert Suconic</a>
 * @author <a href="jmesnil@redhat.com">Jeff Mesnil</a>
 *
 */
public class JournalStorageManager implements StorageManager
{
   private static final Logger log = Logger.getLogger(JournalStorageManager.class);

   private static final long CHECKPOINT_BATCH_SIZE = Integer.MAX_VALUE;

   // Bindings journal record type

   public static final byte QUEUE_BINDING_RECORD = 21;

   public static final byte PERSISTENT_ID_RECORD = 23;

   public static final byte ID_COUNTER_RECORD = 24;

   // type + expiration + timestamp + priority
   public static final int SIZE_FIELDS = SIZE_INT + SIZE_LONG + SIZE_LONG + SIZE_BYTE;

   // Message journal record types
   
   public static final byte ADD_LARGE_MESSAGE = 30;

   public static final byte ADD_MESSAGE = 31;

   public static final byte ADD_REF = 32;

   public static final byte ACKNOWLEDGE_REF = 33;

   public static final byte UPDATE_DELIVERY_COUNT = 34;

   public static final byte PAGE_TRANSACTION = 35;

   public static final byte SET_SCHEDULED_DELIVERY_TIME = 36;

   public static final byte DUPLICATE_ID = 37;

   private UUID persistentID;

   private final BatchingIDGenerator idGenerator = new BatchingIDGenerator(0, CHECKPOINT_BATCH_SIZE);

   private final Journal messageJournal;

   private final Journal bindingsJournal;

   private final SequentialFileFactory largeMessagesFactory;

   private volatile boolean started;

   private final Executor executor;

   private final boolean syncTransactional;

   private final boolean syncNonTransactional;

   private final int perfBlastPages;
   
   private final boolean createBindingsDir;
   
   private final String bindingsDir;
   
   private final boolean createJournalDir;
   
   private final String journalDir;
   
   private final String largeMessagesDirectory;

   public JournalStorageManager(final Configuration config, final Executor executor)
   {
      this.executor = executor;

      if (config.getJournalType() != JournalType.NIO && config.getJournalType() != JournalType.ASYNCIO)
      {
         throw new IllegalArgumentException("Only NIO and AsyncIO are supported journals");
      }

      bindingsDir = config.getBindingsDirectory();

      if (bindingsDir == null)
      {
         throw new NullPointerException("bindings-dir is null");
      }
      
      createBindingsDir = config.isCreateBindingsDir();

      journalDir = config.getJournalDirectory();

      if (journalDir == null)
      {
         throw new NullPointerException("journal-dir is null");
      }
      
      createJournalDir = config.isCreateJournalDir();

      SequentialFileFactory bindingsFF = new NIOSequentialFileFactory(bindingsDir);

      bindingsJournal = new JournalImpl(1024 * 1024,
                                        2,
                                        config.getJournalCompactMinFiles(),
                                        config.getJournalCompactPercentage(),
                                        bindingsFF,
                                        "hornetq-bindings",
                                        "bindings",
                                        1);

      syncNonTransactional = config.isJournalSyncNonTransactional();

      syncTransactional = config.isJournalSyncTransactional();

      SequentialFileFactory journalFF = null;

      if (config.getJournalType() == JournalType.ASYNCIO)
      {
         log.info("AIO journal selected");
         if (!AIOSequentialFileFactory.isSupported())
         {
            log.warn("AIO wasn't located on this platform, it will fall back to using pure Java NIO. " + "If your platform is Linux, install LibAIO to enable the AIO journal");
            journalFF = new NIOSequentialFileFactory(journalDir);
         }
         else
         {
            journalFF = new AIOSequentialFileFactory(journalDir,
                                                     config.getAIOBufferSize(),
                                                     config.getAIOBufferTimeout(),
                                                     config.isAIOFlushOnSync(),
                                                     config.isLogJournalWriteRate());
            log.info("AIO loaded successfully");
         }
      }
      else if (config.getJournalType() == JournalType.NIO)
      {
         log.info("NIO Journal selected");
         journalFF = new NIOSequentialFileFactory(journalDir);
      }
      else
      {
         throw new IllegalArgumentException("Unsupported journal type " + config.getJournalType());
      }

      messageJournal = new JournalImpl(config.getJournalFileSize(),
                                       config.getJournalMinFiles(),
                                       config.getJournalCompactMinFiles(),
                                       config.getJournalCompactPercentage(),
                                       journalFF,
                                       "hornetq-data",
                                       "hq",
                                       config.getJournalMaxAIO());

      largeMessagesDirectory = config.getLargeMessagesDirectory();

      largeMessagesFactory = new NIOSequentialFileFactory(largeMessagesDirectory);

      perfBlastPages = config.getJournalPerfBlastPages();
   }

   public UUID getPersistentID()
   {
      return persistentID;
   }

   public void setPersistentID(UUID id) throws Exception
   {
      long recordID = generateUniqueID();

      if (id != null)
      {
         bindingsJournal.appendAddRecord(recordID, PERSISTENT_ID_RECORD, new PersistentIDEncoding(id), true);
      }

      this.persistentID = id;
   }

   public long generateUniqueID()
   {
      long id = idGenerator.generateID();

      return id;
   }

   public long getCurrentUniqueID()
   {
      return idGenerator.getCurrentID();
   }

   public void setUniqueIDSequence(final long id)
   {
      idGenerator.setID(id);
   }

   public LargeServerMessage createLargeMessage()
   {
      return new JournalLargeServerMessage(this);
   }

   // Non transactional operations

   public void storeMessage(final ServerMessage message) throws Exception
   {
      if (message.getMessageID() <= 0)
      {
         throw new HornetQException(HornetQException.ILLEGAL_STATE, "MessageId was not assigned to Message");
      }

      // Note that we don't sync, the add reference that comes immediately after will sync

      if (message.isLargeMessage())
      {
         messageJournal.appendAddRecord(message.getMessageID(),
                                        ADD_LARGE_MESSAGE,
                                        new LargeMessageEncoding((LargeServerMessage)message),
                                        false);
      }
      else
      {
         messageJournal.appendAddRecord(message.getMessageID(), ADD_MESSAGE, message, false);
      }
   }

   public void storeReference(final long queueID, final long messageID) throws Exception
   {
      messageJournal.appendUpdateRecord(messageID, ADD_REF, new RefEncoding(queueID), syncNonTransactional);
   }

   public void storeAcknowledge(final long queueID, final long messageID) throws Exception
   {
      messageJournal.appendUpdateRecord(messageID, ACKNOWLEDGE_REF, new RefEncoding(queueID), syncNonTransactional);
   }

   public void deleteMessage(final long messageID) throws Exception
   {
      messageJournal.appendDeleteRecord(messageID, syncNonTransactional);
   }

   public void updateScheduledDeliveryTime(final MessageReference ref) throws Exception
   {
      ScheduledDeliveryEncoding encoding = new ScheduledDeliveryEncoding(ref.getScheduledDeliveryTime(),
                                                                         ref.getQueue().getPersistenceID());

      messageJournal.appendUpdateRecord(ref.getMessage().getMessageID(),
                                        SET_SCHEDULED_DELIVERY_TIME,
                                        encoding,
                                        syncNonTransactional);
   }

   public void storeDuplicateID(final SimpleString address, final byte[] duplID, final long recordID) throws Exception
   {
      DuplicateIDEncoding encoding = new DuplicateIDEncoding(address, duplID);

      messageJournal.appendAddRecord(recordID, DUPLICATE_ID, encoding, syncNonTransactional);
   }

   public void deleteDuplicateID(long recordID) throws Exception
   {
      messageJournal.appendDeleteRecord(recordID, syncNonTransactional);
   }

   // Transactional operations

   public void storeMessageTransactional(final long txID, final ServerMessage message) throws Exception
   {
      if (message.getMessageID() <= 0)
      {
         throw new HornetQException(HornetQException.ILLEGAL_STATE, "MessageId was not assigned to Message");
      }

      if (message.isLargeMessage())
      {
         messageJournal.appendAddRecordTransactional(txID,
                                                     message.getMessageID(),
                                                     ADD_LARGE_MESSAGE,
                                                     new LargeMessageEncoding(((LargeServerMessage)message)));
      }
      else
      {
         messageJournal.appendAddRecordTransactional(txID, message.getMessageID(), ADD_MESSAGE, message);
      }

   }
   
   public void storePageTransaction(final long txID, final PageTransactionInfo pageTransaction) throws Exception
   {
      if (pageTransaction.getRecordID() != 0)
      {
         // Instead of updating the record, we delete the old one as that is
         // better for reclaiming
         messageJournal.appendDeleteRecordTransactional(txID, pageTransaction.getRecordID());
      }

      pageTransaction.setRecordID(generateUniqueID());

      messageJournal.appendAddRecordTransactional(txID,
                                                  pageTransaction.getRecordID(),
                                                  PAGE_TRANSACTION,
                                                  pageTransaction);
   }

   public void storeReferenceTransactional(final long txID, final long queueID, final long messageID) throws Exception
   {
      messageJournal.appendUpdateRecordTransactional(txID, messageID, ADD_REF, new RefEncoding(queueID));
   }

   public void storeAcknowledgeTransactional(final long txID, final long queueID, final long messageID) throws Exception
   {
      messageJournal.appendUpdateRecordTransactional(txID, messageID, ACKNOWLEDGE_REF, new RefEncoding(queueID));
   }

   public void deletePageTransactional(final long txID, final long recordID) throws Exception
   {
      messageJournal.appendDeleteRecordTransactional(txID, recordID);
   }

   public void updateScheduledDeliveryTimeTransactional(final long txID, final MessageReference ref) throws Exception
   {
      ScheduledDeliveryEncoding encoding = new ScheduledDeliveryEncoding(ref.getScheduledDeliveryTime(),
                                                                         ref.getQueue().getPersistenceID());

      messageJournal.appendUpdateRecordTransactional(txID,
                                                     ref.getMessage().getMessageID(),
                                                     SET_SCHEDULED_DELIVERY_TIME,
                                                     encoding);
   }

   public void deleteMessageTransactional(final long txID, final long queueID, final long messageID) throws Exception
   {
      messageJournal.appendDeleteRecordTransactional(txID, messageID, new DeleteEncoding(queueID));
   }

   public void prepare(final long txID, final Xid xid) throws Exception
   {
      messageJournal.appendPrepareRecord(txID, new XidEncoding(xid), syncTransactional);
   }

   public void commit(final long txID) throws Exception
   {
      messageJournal.appendCommitRecord(txID, syncTransactional);
   }

   public void rollback(final long txID) throws Exception
   {
      messageJournal.appendRollbackRecord(txID, syncTransactional);
   }

   public void storeDuplicateIDTransactional(final long txID,
                                             final SimpleString address,
                                             final byte[] duplID,
                                             final long recordID) throws Exception
   {
      DuplicateIDEncoding encoding = new DuplicateIDEncoding(address, duplID);

      messageJournal.appendAddRecordTransactional(txID, recordID, DUPLICATE_ID, encoding);
   }

   public void updateDuplicateIDTransactional(final long txID,
                                              final SimpleString address,
                                              final byte[] duplID,
                                              final long recordID) throws Exception
   {
      DuplicateIDEncoding encoding = new DuplicateIDEncoding(address, duplID);

      messageJournal.appendUpdateRecordTransactional(txID, recordID, DUPLICATE_ID, encoding);
   }

   public void deleteDuplicateIDTransactional(long txID, long recordID) throws Exception
   {
      messageJournal.appendDeleteRecordTransactional(txID, recordID);
   }

   // Other operations

   public void updateDeliveryCount(final MessageReference ref) throws Exception
   {
      DeliveryCountUpdateEncoding updateInfo = new DeliveryCountUpdateEncoding(ref.getQueue().getPersistenceID(),
                                                                               ref.getDeliveryCount());

      messageJournal.appendUpdateRecord(ref.getMessage().getMessageID(),
                                        UPDATE_DELIVERY_COUNT,
                                        updateInfo,
                                        syncNonTransactional);
   }

   private static final class AddMessageRecord
   {
      public AddMessageRecord(final ServerMessage message)
      {
         this.message = message;
      }

      final ServerMessage message;

      long scheduledDeliveryTime;

      int deliveryCount;
   }

   public void loadMessageJournal(final PagingManager pagingManager,
                                  final ResourceManager resourceManager,
                                  final Map<Long, Queue> queues,
                                  final Map<SimpleString, List<Pair<byte[], Long>>> duplicateIDMap) throws Exception
   {
      List<RecordInfo> records = new ArrayList<RecordInfo>();

      List<PreparedTransactionInfo> preparedTransactions = new ArrayList<PreparedTransactionInfo>();

      messageJournal.load(records, preparedTransactions);

      Map<Long, ServerMessage> messages = new HashMap<Long, ServerMessage>();

      Map<Long, Map<Long, AddMessageRecord>> queueMap = new HashMap<Long, Map<Long, AddMessageRecord>>();

      for (RecordInfo record : records)
      {
         byte[] data = record.data;

         HornetQBuffer buff = ChannelBuffers.wrappedBuffer(data);

         byte recordType = record.getUserRecordType();

         switch (recordType)
         {
            case ADD_LARGE_MESSAGE:
            {
               LargeServerMessage largeMessage = createLargeMessage();

               LargeMessageEncoding messageEncoding = new LargeMessageEncoding(largeMessage);

               messageEncoding.decode(buff);
               
               Long originalMessageID = (Long)largeMessage.getProperties().getProperty(MessageImpl.HDR_ORIG_MESSAGE_ID);
               
               // Using the linked file by the original file
               if (originalMessageID != null)
               {
                  LargeServerMessage originalMessage = (LargeServerMessage)messages.get(originalMessageID);
                  
                  if (originalMessage == null)
                  {
                     // this could happen if the message was deleted but the file still exists as the file still being used
                      originalMessage = createLargeMessage();
                     originalMessage.setMessageID(originalMessageID);
                     originalMessage.setComplete(true);
                     messages.put(originalMessageID, originalMessage);
                  }
                  
                  originalMessage.incrementRefCount();
                  
                  largeMessage.setLinkedMessage(originalMessage);
                  largeMessage.setComplete(true);
               }
               

               messages.put(record.id, largeMessage);

               break;
            }
            case ADD_MESSAGE:
            {
               ServerMessage message = new ServerMessageImpl(record.id);

               message.decode(buff);

               messages.put(record.id, message);

               break;
            }
            case ADD_REF:
            {
               long messageID = record.id;

               RefEncoding encoding = new RefEncoding();

               encoding.decode(buff);

               Map<Long, AddMessageRecord> queueMessages = queueMap.get(encoding.queueID);

               if (queueMessages == null)
               {
                  queueMessages = new LinkedHashMap<Long, AddMessageRecord>();

                  queueMap.put(encoding.queueID, queueMessages);
               }

               ServerMessage message = messages.get(messageID);

               if (message == null)
               {
                  throw new IllegalStateException("Cannot find message " + record.id);
               }

               queueMessages.put(messageID, new AddMessageRecord(message));

               break;
            }
            case ACKNOWLEDGE_REF:
            {
               long messageID = record.id;

               RefEncoding encoding = new RefEncoding();

               encoding.decode(buff);

               Map<Long, AddMessageRecord> queueMessages = queueMap.get(encoding.queueID);

               if (queueMessages == null)
               {
                  throw new IllegalStateException("Cannot find queue messages " + encoding.queueID);
               }

               AddMessageRecord rec = queueMessages.remove(messageID);

               if (rec == null)
               {
                  throw new IllegalStateException("Cannot find message " + messageID);
               }

               break;
            }
            case UPDATE_DELIVERY_COUNT:
            {
               long messageID = record.id;

               DeliveryCountUpdateEncoding encoding = new DeliveryCountUpdateEncoding();

               encoding.decode(buff);

               Map<Long, AddMessageRecord> queueMessages = queueMap.get(encoding.queueID);

               if (queueMessages == null)
               {
                  throw new IllegalStateException("Cannot find queue messages " + encoding.queueID);
               }

               AddMessageRecord rec = queueMessages.get(messageID);

               if (rec == null)
               {
                  throw new IllegalStateException("Cannot find message " + messageID);
               }

               rec.deliveryCount = encoding.count;

               break;
            }
            case PAGE_TRANSACTION:
            {
               PageTransactionInfoImpl pageTransactionInfo = new PageTransactionInfoImpl();

               pageTransactionInfo.decode(buff);

               pageTransactionInfo.setRecordID(record.id);

               pagingManager.addTransaction(pageTransactionInfo);

               break;
            }
            case SET_SCHEDULED_DELIVERY_TIME:
            {
               long messageID = record.id;

               ScheduledDeliveryEncoding encoding = new ScheduledDeliveryEncoding();

               encoding.decode(buff);

               Map<Long, AddMessageRecord> queueMessages = queueMap.get(encoding.queueID);

               if (queueMessages == null)
               {
                  throw new IllegalStateException("Cannot find queue messages " + encoding.queueID);
               }

               AddMessageRecord rec = queueMessages.get(messageID);

               if (rec == null)
               {
                  throw new IllegalStateException("Cannot find message " + messageID);
               }

               rec.scheduledDeliveryTime = encoding.scheduledDeliveryTime;

               break;
            }
            case DUPLICATE_ID:
            {
               DuplicateIDEncoding encoding = new DuplicateIDEncoding();

               encoding.decode(buff);

               List<Pair<byte[], Long>> ids = duplicateIDMap.get(encoding.address);

               if (ids == null)
               {
                  ids = new ArrayList<Pair<byte[], Long>>();

                  duplicateIDMap.put(encoding.address, ids);
               }

               ids.add(new Pair<byte[], Long>(encoding.duplID, record.id));

               break;
            }
            default:
            {
               throw new IllegalStateException("Invalid record type " + recordType);
            }
         }
      }

      for (Map.Entry<Long, Map<Long, AddMessageRecord>> entry : queueMap.entrySet())
      {
         long queueID = entry.getKey();

         Map<Long, AddMessageRecord> queueRecords = entry.getValue();

         Queue queue = queues.get(queueID);

         for (AddMessageRecord record : queueRecords.values())
         {
            long scheduledDeliveryTime = record.scheduledDeliveryTime;

            if (scheduledDeliveryTime != 0)
            {
               record.message.putLongProperty(MessageImpl.HDR_SCHEDULED_DELIVERY_TIME, scheduledDeliveryTime);
            }

            MessageReference ref = queue.reroute(record.message, null);

            ref.setDeliveryCount(record.deliveryCount);

            if (scheduledDeliveryTime != 0)
            {
               record.message.removeProperty(MessageImpl.HDR_SCHEDULED_DELIVERY_TIME);
            }
         }
      }

      loadPreparedTransactions(pagingManager, resourceManager, queues, preparedTransactions, duplicateIDMap);

      if (perfBlastPages != -1)
      {
         messageJournal.perfBlast(perfBlastPages);
      }
   }

   private void loadPreparedTransactions(final PagingManager pagingManager,
                                         final ResourceManager resourceManager,
                                         final Map<Long, Queue> queues,
                                         final List<PreparedTransactionInfo> preparedTransactions,
                                         final Map<SimpleString, List<Pair<byte[], Long>>> duplicateIDMap) throws Exception
   {
      // recover prepared transactions
      for (PreparedTransactionInfo preparedTransaction : preparedTransactions)
      {
         XidEncoding encodingXid = new XidEncoding(preparedTransaction.extraData);

         Xid xid = encodingXid.xid;

         Transaction tx = new TransactionImpl(preparedTransaction.id, xid, this);

         List<MessageReference> referencesToAck = new ArrayList<MessageReference>();

         Map<Long, ServerMessage> messages = new HashMap<Long, ServerMessage>();

         // Use same method as load message journal to prune out acks, so they don't get added.
         // Then have reacknowledge(tx) methods on queue, which needs to add the page size

         // first get any sent messages for this tx and recreate
         for (RecordInfo record : preparedTransaction.records)
         {
            byte[] data = record.data;

            HornetQBuffer buff = ChannelBuffers.wrappedBuffer(data);

            byte recordType = record.getUserRecordType();

            switch (recordType)
            {
               case ADD_MESSAGE:
               {
                  ServerMessage message = new ServerMessageImpl(record.id);

                  message.decode(buff);

                  messages.put(record.id, message);

                  break;
               }
               case ADD_REF:
               {
                  long messageID = record.id;

                  RefEncoding encoding = new RefEncoding();

                  encoding.decode(buff);

                  Queue queue = queues.get(encoding.queueID);

                  if (queue == null)
                  {
                     throw new IllegalStateException("Cannot find queue with id " + encoding.queueID);
                  }

                  ServerMessage message = messages.get(messageID);

                  if (message == null)
                  {
                     throw new IllegalStateException("Cannot find message with id " + messageID);
                  }

                  queue.reroute(message, tx);

                  break;
               }
               case ACKNOWLEDGE_REF:
               {
                  long messageID = record.id;

                  RefEncoding encoding = new RefEncoding();

                  encoding.decode(buff);

                  Queue queue = queues.get(encoding.queueID);

                  if (queue == null)
                  {
                     throw new IllegalStateException("Cannot find queue with id " + encoding.queueID);
                  }

                  // TODO - this involves a scan - we should find a quicker qay of doing it
                  MessageReference removed = queue.removeReferenceWithID(messageID);

                  referencesToAck.add(removed);

                  if (removed == null)
                  {
                     throw new IllegalStateException("Failed to remove reference for " + messageID);
                  }

                  break;
               }
               case PAGE_TRANSACTION:
               {
                  PageTransactionInfo pageTransactionInfo = new PageTransactionInfoImpl();

                  pageTransactionInfo.decode(buff);

                  pageTransactionInfo.markIncomplete();

                  tx.putProperty(TransactionPropertyIndexes.PAGE_TRANSACTION, pageTransactionInfo);

                  pagingManager.addTransaction(pageTransactionInfo);

                  tx.addOperation(new FinishPageMessageOperation());

                  break;
               }
               case SET_SCHEDULED_DELIVERY_TIME:
               {
                  // Do nothing - for prepared txs, the set scheduled delivery time will only occur in a send in which
                  // case the message will already have the header for the scheduled delivery time, so no need to do
                  // anything.

                  break;
               }
               case DUPLICATE_ID:
               {
                  // We need load the duplicate ids at prepare time too
                  DuplicateIDEncoding encoding = new DuplicateIDEncoding();

                  encoding.decode(buff);

                  List<Pair<byte[], Long>> ids = duplicateIDMap.get(encoding.address);

                  if (ids == null)
                  {
                     ids = new ArrayList<Pair<byte[], Long>>();

                     duplicateIDMap.put(encoding.address, ids);
                  }

                  ids.add(new Pair<byte[], Long>(encoding.duplID, record.id));

                  break;
               }
               default:
               {
                  log.warn("InternalError: Record type " + recordType +
                           " not recognized. Maybe you're using journal files created on a different version");
               }
            }
         }

         for (RecordInfo record : preparedTransaction.recordsToDelete)
         {
            byte[] data = record.data;

            HornetQBuffer buff = ChannelBuffers.wrappedBuffer(data);

            long messageID = record.id;

            DeleteEncoding encoding = new DeleteEncoding();

            encoding.decode(buff);

            Queue queue = queues.get(encoding.queueID);

            if (queue == null)
            {
               throw new IllegalStateException("Cannot find queue with id " + encoding.queueID);
            }

            MessageReference removed = queue.removeReferenceWithID(messageID);

            if (removed != null)
            {
               referencesToAck.add(removed);
            }

         }

         for (MessageReference ack : referencesToAck)
         {
            ack.getQueue().reacknowledge(tx, ack);
         }

         tx.setState(Transaction.State.PREPARED);

         resourceManager.putTransaction(xid, tx);
      }
   }

   // Bindings operations

   public void addQueueBinding(final Binding binding) throws Exception
   {
      Queue queue = (Queue)binding.getBindable();

      Filter filter = queue.getFilter();

      SimpleString filterString = filter == null ? null : filter.getFilterString();

      PersistentQueueBindingEncoding bindingEncoding = new PersistentQueueBindingEncoding(queue.getName(),
                                                                                          binding.getAddress(),
                                                                                          filterString);

      long id = this.generateUniqueID();

      queue.setPersistenceID(id);

      bindingsJournal.appendAddRecord(id, QUEUE_BINDING_RECORD, bindingEncoding, true);
   }

   public void deleteQueueBinding(final long queueBindingID) throws Exception
   {
      bindingsJournal.appendDeleteRecord(queueBindingID, true);
   }

   public void loadBindingJournal(final List<QueueBindingInfo> queueBindingInfos) throws Exception
   {
      List<RecordInfo> records = new ArrayList<RecordInfo>();

      List<PreparedTransactionInfo> preparedTransactions = new ArrayList<PreparedTransactionInfo>();

      bindingsJournal.load(records, preparedTransactions);

      long lastID = -1;

      for (RecordInfo record : records)
      {
         long id = record.id;

         HornetQBuffer buffer = ChannelBuffers.wrappedBuffer(record.data);

         byte rec = record.getUserRecordType();

         if (rec == QUEUE_BINDING_RECORD)
         {
            PersistentQueueBindingEncoding bindingEncoding = new PersistentQueueBindingEncoding();

            bindingEncoding.decode(buffer);

            bindingEncoding.setPersistenceID(id);

            queueBindingInfos.add(bindingEncoding);
         }
         else if (rec == PERSISTENT_ID_RECORD)
         {
            PersistentIDEncoding encoding = new PersistentIDEncoding();

            encoding.decode(buffer);

            persistentID = encoding.uuid;
         }
         else if (rec == ID_COUNTER_RECORD)
         {
            IDCounterEncoding encoding = new IDCounterEncoding();

            encoding.decode(buffer);

            lastID = encoding.id;
         }
         else
         {
            throw new IllegalStateException("Invalid record type " + rec);
         }
      }

      idGenerator.setID(lastID + 1);
   }

   // MessagingComponent implementation
   // ------------------------------------------------------

   public synchronized void start() throws Exception
   {
      if (started)
      {
         return;
      }


      checkAndCreateDir(bindingsDir, createBindingsDir);

      checkAndCreateDir(journalDir, createJournalDir);
      
      checkAndCreateDir(largeMessagesDirectory, createJournalDir);


      cleanupIncompleteFiles();

      bindingsJournal.start();

      messageJournal.start();

      started = true;
   }

   public synchronized void stop() throws Exception
   {
      if (!started)
      {
         return;
      }

      // Must call close to make sure last id is persisted
      idGenerator.close();

      bindingsJournal.stop();

      messageJournal.stop();

      persistentID = null;

      started = false;
   }

   public synchronized boolean isStarted()
   {
      return started;
   }

   // Public -----------------------------------------------------------------------------------

   public Journal getMessageJournal()
   {
      return messageJournal;
   }

   public Journal getBindingsJournal()
   {
      return bindingsJournal;
   }

   // Package protected ---------------------------------------------

   // This should be accessed from this package only
   void deleteFile(final SequentialFile file)
   {
      this.executor.execute(new Runnable()
      {
         public void run()
         {
            try
            {
               file.delete();
            }
            catch (Exception e)
            {
               log.warn(e.getMessage(), e);
            }
         }

      });
   }

   /**
    * @param messageID
    * @return
    */
   SequentialFile createFileForLargeMessage(final long messageID, final boolean completeFile)
   {
      if (completeFile)
      {
         return largeMessagesFactory.createSequentialFile(messageID + ".msg", -1);
      }
      else
      {
         return largeMessagesFactory.createSequentialFile(messageID + ".tmp", -1);
      }
   }

   // Private ----------------------------------------------------------------------------------

   private void checkAndCreateDir(final String dir, final boolean create)
   {
      File f = new File(dir);

      if (!f.exists())
      {
         if (create)
         {
            if (!f.mkdirs())
            {
               throw new IllegalStateException("Failed to create directory " + dir);
            }
         }
         else
         {
            throw new IllegalArgumentException("Directory " + dir + " does not exist and will not create it");
         }
      }
   }

   /**
    * @throws Exception
    */
   private void cleanupIncompleteFiles() throws Exception
   {
      if (largeMessagesFactory != null)
      {
         List<String> tmpFiles = this.largeMessagesFactory.listFiles("tmp");
         for (String tmpFile : tmpFiles)
         {
            SequentialFile file = largeMessagesFactory.createSequentialFile(tmpFile, -1);
            file.delete();
         }
      }
   }

   // Inner Classes
   // ----------------------------------------------------------------------------

   private class BatchingIDGenerator implements IDGenerator
   {
      private final AtomicLong counter;

      private final long checkpointSize;

      private volatile long nextID;

      public BatchingIDGenerator(final long start, final long checkpointSize)
      {
         this.counter = new AtomicLong(start);

         this.checkpointSize = checkpointSize;

         nextID = start + checkpointSize;
      }

      public void setID(final long id)
      {
         this.counter.set(id);

         nextID = id + checkpointSize;
      }

      public long generateID()
      {
         long id = counter.getAndIncrement();

         if (id >= nextID)
         {
            saveCheckPoint(id);

            return id;
         }
         else
         {
            return id;
         }
      }

      private synchronized void saveCheckPoint(final long id)
      {
         if (id >= nextID)
         {
            storeID(id);

            nextID += checkpointSize;
         }
      }

      public long getCurrentID()
      {
         return counter.get();
      }

      public void close()
      {
         storeID(counter.get());
      }

      private void storeID(final long id)
      {
         try
         {
            bindingsJournal.appendAddRecord(id, ID_COUNTER_RECORD, new IDCounterEncoding(id), true);
         }
         catch (Exception e)
         {
            log.error("Failed to store id", e);
         }
      }
   }

   private static class XidEncoding implements EncodingSupport
   {
      final Xid xid;

      XidEncoding(final Xid xid)
      {
         this.xid = xid;
      }

      XidEncoding(final byte[] data)
      {
         xid = XidCodecSupport.decodeXid(ChannelBuffers.wrappedBuffer(data));
      }

      public void decode(final HornetQBuffer buffer)
      {
         throw new IllegalStateException("Non Supported Operation");
      }

      public void encode(final HornetQBuffer buffer)
      {
         XidCodecSupport.encodeXid(xid, buffer);
      }

      public int getEncodeSize()
      {
         return XidCodecSupport.getXidEncodeLength(xid);
      }
   }

   private static class PersistentQueueBindingEncoding implements EncodingSupport, QueueBindingInfo
   {
      long persistenceID;

      SimpleString name;

      SimpleString address;

      SimpleString filterString;

      public PersistentQueueBindingEncoding()
      {
      }

      public PersistentQueueBindingEncoding(final SimpleString name,
                                            final SimpleString address,
                                            final SimpleString filterString)
      {
         this.name = name;
         this.address = address;
         this.filterString = filterString;
      }

      public long getPersistenceID()
      {
         return persistenceID;
      }

      public void setPersistenceID(final long id)
      {
         this.persistenceID = id;
      }

      public SimpleString getAddress()
      {
         return address;
      }

      public SimpleString getFilterString()
      {
         return filterString;
      }

      public SimpleString getQueueName()
      {
         return name;
      }

      public void decode(final HornetQBuffer buffer)
      {
         name = buffer.readSimpleString();
         address = buffer.readSimpleString();
         filterString = buffer.readNullableSimpleString();
      }

      public void encode(final HornetQBuffer buffer)
      {
         buffer.writeSimpleString(name);
         buffer.writeSimpleString(address);
         buffer.writeNullableSimpleString(filterString);
      }

      public int getEncodeSize()
      {
         return SimpleString.sizeofString(name) + SimpleString.sizeofString(address) +
                SimpleString.sizeofNullableString(filterString);
      }
   }

   private static class PersistentIDEncoding implements EncodingSupport
   {
      UUID uuid;

      PersistentIDEncoding(final UUID uuid)
      {
         this.uuid = uuid;
      }

      PersistentIDEncoding()
      {
      }

      public void decode(final HornetQBuffer buffer)
      {
         byte[] bytes = new byte[16];

         buffer.readBytes(bytes);

         uuid = new UUID(UUID.TYPE_TIME_BASED, bytes);
      }

      public void encode(final HornetQBuffer buffer)
      {
         buffer.writeBytes(uuid.asBytes());
      }

      public int getEncodeSize()
      {
         return 16;
      }

   }

   private static class IDCounterEncoding implements EncodingSupport
   {
      long id;

      IDCounterEncoding(final long id)
      {
         this.id = id;
      }

      IDCounterEncoding()
      {
      }

      public void decode(final HornetQBuffer buffer)
      {
         id = buffer.readLong();
      }

      public void encode(final HornetQBuffer buffer)
      {
         buffer.writeLong(id);
      }

      public int getEncodeSize()
      {
         return SIZE_LONG;
      }

   }

   private static class LargeMessageEncoding implements EncodingSupport
   {
      private final LargeServerMessage message;

      public LargeMessageEncoding(LargeServerMessage message)
      {
         this.message = message;
      }

      /* (non-Javadoc)
       * @see org.hornetq.core.journal.EncodingSupport#decode(org.hornetq.core.remoting.spi.HornetQBuffer)
       */
      public void decode(final HornetQBuffer buffer)
      {
         message.decode(buffer);
      }

      /* (non-Javadoc)
       * @see org.hornetq.core.journal.EncodingSupport#encode(org.hornetq.core.remoting.spi.HornetQBuffer)
       */
      public void encode(final HornetQBuffer buffer)
      {
         message.encode(buffer);
      }

      /* (non-Javadoc)
       * @see org.hornetq.core.journal.EncodingSupport#getEncodeSize()
       */
      public int getEncodeSize()
      {
         return message.getEncodeSize();
      }

   }

   private static class DeliveryCountUpdateEncoding implements EncodingSupport
   {
      long queueID;

      int count;

      public DeliveryCountUpdateEncoding()
      {
         super();
      }

      public DeliveryCountUpdateEncoding(final long queueID, final int count)
      {
         super();
         this.queueID = queueID;
         this.count = count;
      }

      public void decode(final HornetQBuffer buffer)
      {
         queueID = buffer.readLong();
         count = buffer.readInt();
      }

      public void encode(final HornetQBuffer buffer)
      {
         buffer.writeLong(queueID);
         buffer.writeInt(count);
      }

      public int getEncodeSize()
      {
         return 8 + 4;
      }
   }

   private static class QueueEncoding implements EncodingSupport
   {
      long queueID;

      public QueueEncoding(final long queueID)
      {
         super();
         this.queueID = queueID;
      }

      public QueueEncoding()
      {
         super();
      }

      public void decode(final HornetQBuffer buffer)
      {
         queueID = buffer.readLong();
      }

      public void encode(final HornetQBuffer buffer)
      {
         buffer.writeLong(queueID);
      }

      public int getEncodeSize()
      {
         return 8;
      }
   }

   private static class DeleteEncoding extends QueueEncoding
   {
      public DeleteEncoding()
      {
         super();
      }

      public DeleteEncoding(final long queueID)
      {
         super(queueID);
      }
   }

   private static class RefEncoding extends QueueEncoding
   {
      public RefEncoding()
      {
         super();
      }

      public RefEncoding(final long queueID)
      {
         super(queueID);
      }
   }

   private static class ScheduledDeliveryEncoding extends QueueEncoding
   {

      long scheduledDeliveryTime;

      private ScheduledDeliveryEncoding(long scheduledDeliveryTime, long queueID)
      {
         super(queueID);
         this.scheduledDeliveryTime = scheduledDeliveryTime;
      }

      public ScheduledDeliveryEncoding()
      {
      }

      public int getEncodeSize()
      {
         return super.getEncodeSize() + 8;
      }

      public void encode(HornetQBuffer buffer)
      {
         super.encode(buffer);
         buffer.writeLong(scheduledDeliveryTime);
      }

      public void decode(HornetQBuffer buffer)
      {
         super.decode(buffer);
         scheduledDeliveryTime = buffer.readLong();
      }
   }

   private static class DuplicateIDEncoding implements EncodingSupport
   {
      SimpleString address;

      byte[] duplID;

      public DuplicateIDEncoding(final SimpleString address, final byte[] duplID)
      {
         this.address = address;

         this.duplID = duplID;
      }

      public DuplicateIDEncoding()
      {
      }

      public void decode(final HornetQBuffer buffer)
      {
         address = buffer.readSimpleString();

         int size = buffer.readInt();

         duplID = new byte[size];

         buffer.readBytes(duplID);
      }

      public void encode(final HornetQBuffer buffer)
      {
         buffer.writeSimpleString(address);

         buffer.writeInt(duplID.length);

         buffer.writeBytes(duplID);
      }

      public int getEncodeSize()
      {
         return SimpleString.sizeofString(address) + DataConstants.SIZE_INT + duplID.length;
      }
   }

   private class FinishPageMessageOperation implements TransactionOperation
   {

      public void afterCommit(final Transaction tx) throws Exception
      {
         // If part of the transaction goes to the queue, and part goes to paging, we can't let depage start for the
         // transaction until all the messages were added to the queue
         // or else we could deliver the messages out of order

         PageTransactionInfo pageTransaction = (PageTransactionInfo)tx.getProperty(TransactionPropertyIndexes.PAGE_TRANSACTION);

         if (pageTransaction != null)
         {
            pageTransaction.commit();
         }
      }

      public void afterPrepare(final Transaction tx) throws Exception
      {
      }

      public void afterRollback(final Transaction tx) throws Exception
      {
         PageTransactionInfo pageTransaction = (PageTransactionInfo)tx.getProperty(TransactionPropertyIndexes.PAGE_TRANSACTION);

         if (tx.getState() == State.PREPARED && pageTransaction != null)
         {
            pageTransaction.rollback();
         }
      }

      public void beforeCommit(final Transaction tx) throws Exception
      {
      }

      public void beforePrepare(final Transaction tx) throws Exception
      {
      }

      public void beforeRollback(final Transaction tx) throws Exception
      {
      }

      /* (non-Javadoc)
       * @see org.hornetq.core.transaction.TransactionOperation#getDistinctQueues()
       */
      public Collection<Queue> getDistinctQueues()
      {
         return Collections.emptySet();
      }

   }

}
