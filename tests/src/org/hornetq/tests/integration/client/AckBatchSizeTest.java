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
package org.hornetq.tests.integration.client;

import org.hornetq.core.client.ClientConsumer;
import org.hornetq.core.client.ClientMessage;
import org.hornetq.core.client.ClientProducer;
import org.hornetq.core.client.ClientSession;
import org.hornetq.core.client.ClientSessionFactory;
import org.hornetq.core.server.HornetQServer;
import org.hornetq.core.server.Queue;
import org.hornetq.tests.util.ServiceTestBase;
import org.hornetq.utils.SimpleString;

/**
 * @author <a href="mailto:andy.taylor@jboss.org">Andy Taylor</a>
 */
public class AckBatchSizeTest extends ServiceTestBase
{
   public final SimpleString addressA = new SimpleString("addressA");

   public final SimpleString queueA = new SimpleString("queueA");

   public final SimpleString queueB = new SimpleString("queueB");

   public final SimpleString queueC = new SimpleString("queueC");

   /*ackbatchSize tests*/

   /*
   * tests that wed don't acknowledge until the correct ackBatchSize is reached
   * */
   
   private int getMessageEncodeSize(final SimpleString address) throws Exception
   {
      ClientSessionFactory cf = createInVMFactory();
      ClientSession session = cf.createSession(false, true, true);
      ClientMessage message = session.createClientMessage(false);
      // we need to set the destination so we can calculate the encodesize correctly
      message.setDestination(address);
      int encodeSize = message.getEncodeSize();
      session.close();
      cf.close();
      return encodeSize;      
   }

   public void testAckBatchSize() throws Exception
   {
      HornetQServer server = createServer(false);

      try
      {
         server.start();
         ClientSessionFactory cf = createInVMFactory();
         int numMessages = 100;         
         cf.setAckBatchSize(numMessages * getMessageEncodeSize(addressA));
         cf.setBlockOnAcknowledge(true);
         ClientSession sendSession = cf.createSession(false, true, true);
         
         ClientSession session = cf.createSession(false, true, true);
         session.createQueue(addressA, queueA, false);
         ClientProducer cp = sendSession.createProducer(addressA);
         for (int i = 0; i < numMessages; i++)
         {
            cp.send(sendSession.createClientMessage(false));
         }

         ClientConsumer consumer = session.createConsumer(queueA);
         session.start();
         for (int i = 0; i < numMessages - 1; i++)
         {
            ClientMessage m = consumer.receive(5000);
            m.acknowledge();
         }

         ClientMessage m = consumer.receive(5000);
         Queue q = (Queue) server.getPostOffice().getBinding(queueA).getBindable();
         assertEquals(numMessages, q.getDeliveringCount());
         m.acknowledge();
         assertEquals(0, q.getDeliveringCount());
         sendSession.close();
         session.close();
      }
      finally
      {
         if (server.isStarted())
         {
            server.stop();
         }
      }
   }

   /*
   * tests that when the ackBatchSize is 0 we ack every message directly
   * */
   public void testAckBatchSizeZero() throws Exception
   {
      HornetQServer server = createServer(false);

      try
      {
         server.start();
         ClientSessionFactory cf = createInVMFactory();
         cf.setAckBatchSize(0);
         cf.setBlockOnAcknowledge(true);
         ClientSession sendSession = cf.createSession(false, true, true);
         int numMessages = 100;
         
         ClientSession session = cf.createSession(false, true, true);
         session.createQueue(addressA, queueA, false);
         ClientProducer cp = sendSession.createProducer(addressA);
         for (int i = 0; i < numMessages; i++)
         {
            cp.send(sendSession.createClientMessage(false));
         }

         ClientConsumer consumer = session.createConsumer(queueA);
         session.start();
         Queue q = (Queue) server.getPostOffice().getBinding(queueA).getBindable();
         ClientMessage[] messages = new ClientMessage[numMessages];
         for (int i = 0; i < numMessages; i++)
         {
            messages[i] = consumer.receive(5000);
            assertNotNull(messages[i]);
         }
         for (int i = 0; i < numMessages; i++)
         {
            messages[i].acknowledge();
            assertEquals(numMessages - i - 1, q.getDeliveringCount());
         }
         sendSession.close();
         session.close();
      }
      finally
      {
         if (server.isStarted())
         {
            server.stop();
         }
      }
   }
}
