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

package org.hornetq.tests.integration.jms.server.management;

import static org.hornetq.tests.integration.management.ManagementControlHelper.createTopicControl;
import static org.hornetq.tests.util.RandomUtil.randomLong;
import static org.hornetq.tests.util.RandomUtil.randomString;

import java.util.Map;

import javax.jms.Connection;
import javax.jms.Session;
import javax.jms.TopicSubscriber;

import org.hornetq.core.config.Configuration;
import org.hornetq.core.config.TransportConfiguration;
import org.hornetq.core.config.impl.ConfigurationImpl;
import org.hornetq.core.remoting.impl.invm.InVMConnectorFactory;
import org.hornetq.core.server.HornetQ;
import org.hornetq.core.server.HornetQServer;
import org.hornetq.jms.HornetQTopic;
import org.hornetq.jms.server.impl.JMSServerManagerImpl;
import org.hornetq.jms.server.management.SubscriptionInfo;
import org.hornetq.jms.server.management.TopicControl;
import org.hornetq.tests.integration.management.ManagementTestBase;
import org.hornetq.utils.json.JSONArray;

/**
 * A TopicControlTest
 *
 * @author <a href="jmesnil@redhat.com">Jeff Mesnil</a>
 * 
 * Created 13 nov. 2008 16:50:53
 *
 *
 */
public class TopicControlTest extends ManagementTestBase
{

   // Constants -----------------------------------------------------

   // Attributes ----------------------------------------------------

   private HornetQServer server;

   private JMSServerManagerImpl serverManager;

   private String clientID;

   private String subscriptionName;

   protected HornetQTopic topic;

   // Static --------------------------------------------------------

   // Constructors --------------------------------------------------

   // Public --------------------------------------------------------

   public void testGetAttributes() throws Exception
   {
      TopicControl topicControl = createManagementControl();

      assertEquals(topic.getTopicName(), topicControl.getName());
      assertEquals(topic.getAddress(), topicControl.getAddress());
      assertEquals(topic.isTemporary(), topicControl.isTemporary());
      assertEquals(topic.getName(), topicControl.getJNDIBinding());
   }

   public void testGetXXXSubscriptionsCount() throws Exception
   {
      Connection connection_1 = JMSUtil.createConnection(InVMConnectorFactory.class.getName());

      // 1 non-durable subscriber, 2 durable subscribers
      JMSUtil.createConsumer(connection_1, topic);

      Connection connection_2 = JMSUtil.createConnection(InVMConnectorFactory.class.getName());
      JMSUtil.createDurableSubscriber(connection_2, topic, clientID, subscriptionName);
      Connection connection_3 = JMSUtil.createConnection(InVMConnectorFactory.class.getName());
      JMSUtil.createDurableSubscriber(connection_3, topic, clientID, subscriptionName + "2");

      TopicControl topicControl = createManagementControl();
      assertEquals(3, topicControl.getSubscriptionCount());
      assertEquals(1, topicControl.getNonDurableSubscriptionCount());
      assertEquals(2, topicControl.getDurableSubscriptionCount());

      connection_1.close();
      connection_2.close();
      connection_3.close();
   }

   public void testGetXXXMessagesCount() throws Exception
   {
      // 1 non-durable subscriber, 2 durable subscribers
      Connection connection_1 = JMSUtil.createConnection(InVMConnectorFactory.class.getName());
      JMSUtil.createConsumer(connection_1, topic);
      Connection connection_2 = JMSUtil.createConnection(InVMConnectorFactory.class.getName());
      JMSUtil.createDurableSubscriber(connection_2, topic, clientID, subscriptionName);
      Connection connection_3 = JMSUtil.createConnection(InVMConnectorFactory.class.getName());
      JMSUtil.createDurableSubscriber(connection_3, topic, clientID, subscriptionName + "2");

      TopicControl topicControl = createManagementControl();

      assertEquals(0, topicControl.getMessageCount());
      assertEquals(0, topicControl.getNonDurableMessageCount());
      assertEquals(0, topicControl.getDurableMessageCount());

      JMSUtil.sendMessages(topic, 2);

      assertEquals(3 * 2, topicControl.getMessageCount());
      assertEquals(1 * 2, topicControl.getNonDurableMessageCount());
      assertEquals(2 * 2, topicControl.getDurableMessageCount());

      connection_1.close();
      connection_2.close();
      connection_3.close();
   }

   public void testListXXXSubscriptionsCount() throws Exception
   {
      // 1 non-durable subscriber, 2 durable subscribers
      Connection connection_1 = JMSUtil.createConnection(InVMConnectorFactory.class.getName());
      JMSUtil.createConsumer(connection_1, topic);
      Connection connection_2 = JMSUtil.createConnection(InVMConnectorFactory.class.getName());
      JMSUtil.createDurableSubscriber(connection_2, topic, clientID, subscriptionName);
      Connection connection_3 = JMSUtil.createConnection(InVMConnectorFactory.class.getName());
      JMSUtil.createDurableSubscriber(connection_3, topic, clientID, subscriptionName + "2");

      TopicControl topicControl = createManagementControl();
      assertEquals(3, topicControl.listAllSubscriptions().length);
      assertEquals(1, topicControl.listNonDurableSubscriptions().length);
      assertEquals(2, topicControl.listDurableSubscriptions().length);

      connection_1.close();
      connection_2.close();
      connection_3.close();
   }
   
   public void testListXXXSubscriptionsAsJSON() throws Exception
   {
      // 1 non-durable subscriber, 2 durable subscribers
      Connection connection_1 = JMSUtil.createConnection(InVMConnectorFactory.class.getName());
      JMSUtil.createConsumer(connection_1, topic);
      Connection connection_2 = JMSUtil.createConnection(InVMConnectorFactory.class.getName());
      JMSUtil.createDurableSubscriber(connection_2, topic, clientID, subscriptionName);
      Connection connection_3 = JMSUtil.createConnection(InVMConnectorFactory.class.getName());
      JMSUtil.createDurableSubscriber(connection_3, topic, clientID, subscriptionName + "2");

      TopicControl topicControl = createManagementControl();
      String jsonString = topicControl.listDurableSubscriptionsAsJSON();
      SubscriptionInfo[] infos = SubscriptionInfo.from(jsonString);
      assertEquals(2, infos.length);
      assertEquals(clientID, infos[0].getClientID());
      assertEquals(subscriptionName, infos[0].getName());
      assertEquals(clientID, infos[1].getClientID());
      assertEquals(subscriptionName + "2", infos[1].getName());
      
      jsonString = topicControl.listNonDurableSubscriptionsAsJSON();
      infos = SubscriptionInfo.from(jsonString);
      assertEquals(1, infos.length);
      assertEquals(null, infos[0].getClientID());
      assertEquals(null, infos[0].getName());
      
      jsonString = topicControl.listAllSubscriptionsAsJSON();
      infos = SubscriptionInfo.from(jsonString);
      assertEquals(3, infos.length);
      
      connection_1.close();
      connection_2.close();
      connection_3.close();
   }

   public void testCountMessagesForSubscription() throws Exception
   {
      String key = "key";
      long matchingValue = randomLong();
      long unmatchingValue = matchingValue + 1;

      Connection connection = JMSUtil.createConnection(InVMConnectorFactory.class.getName());
      JMSUtil.createDurableSubscriber(connection, topic, clientID, subscriptionName);

      Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);

      JMSUtil.sendMessageWithProperty(session, topic, key, matchingValue);
      JMSUtil.sendMessageWithProperty(session, topic, key, unmatchingValue);
      JMSUtil.sendMessageWithProperty(session, topic, key, matchingValue);

      TopicControl topicControl = createManagementControl();

      assertEquals(3, topicControl.getMessageCount());

      assertEquals(2, topicControl.countMessagesForSubscription(clientID, subscriptionName, key + " =" + matchingValue));
      assertEquals(1, topicControl.countMessagesForSubscription(clientID, subscriptionName, key + " =" +
                                                                                            unmatchingValue));

      connection.close();
   }

   public void testCountMessagesForUnknownSubscription() throws Exception
   {
      String unknownSubscription = randomString();

      TopicControl topicControl = createManagementControl();

      try
      {
         topicControl.countMessagesForSubscription(clientID, unknownSubscription, null);
         fail();
      }
      catch (Exception e)
      {
      }
   }

   public void testCountMessagesForUnknownClientID() throws Exception
   {
      String unknownClientID = randomString();

      TopicControl topicControl = createManagementControl();

      try
      {
         topicControl.countMessagesForSubscription(unknownClientID, subscriptionName, null);
         fail();
      }
      catch (Exception e)
      {
      }
   }

   public void testDropDurableSubscriptionWithExistingSubscription() throws Exception
   {
      Connection connection = JMSUtil.createConnection(InVMConnectorFactory.class.getName());

      JMSUtil.createDurableSubscriber(connection, topic, clientID, subscriptionName);

      TopicControl topicControl = createManagementControl();
      assertEquals(1, topicControl.getDurableSubscriptionCount());

      connection.close();

      topicControl.dropDurableSubscription(clientID, subscriptionName);

      assertEquals(0, topicControl.getDurableSubscriptionCount());
   }

   public void testDropDurableSubscriptionWithUnknownSubscription() throws Exception
   {
      Connection connection = JMSUtil.createConnection(InVMConnectorFactory.class.getName());

      JMSUtil.createDurableSubscriber(connection, topic, clientID, subscriptionName);

      TopicControl topicControl = createManagementControl();
      assertEquals(1, topicControl.getDurableSubscriptionCount());

      try
      {
         topicControl.dropDurableSubscription(clientID, "this subscription does not exist");
         fail("should throw an exception");
      }
      catch (Exception e)
      {

      }

      assertEquals(1, topicControl.getDurableSubscriptionCount());

      connection.close();
   }

   public void testDropAllSubscriptions() throws Exception
   {
      Connection connection_1 = JMSUtil.createConnection(InVMConnectorFactory.class.getName());
      TopicSubscriber durableSubscriber_1 = JMSUtil.createDurableSubscriber(connection_1,
                                                                            topic,
                                                                            clientID,
                                                                            subscriptionName);
      Connection connection_2 = JMSUtil.createConnection(InVMConnectorFactory.class.getName());
      TopicSubscriber durableSubscriber_2 = JMSUtil.createDurableSubscriber(connection_2,
                                                                            topic,
                                                                            clientID,
                                                                            subscriptionName + "2");

      TopicControl topicControl = createManagementControl();
      assertEquals(2, topicControl.getSubscriptionCount());

      durableSubscriber_1.close();
      durableSubscriber_2.close();

      assertEquals(2, topicControl.getSubscriptionCount());
      topicControl.dropAllSubscriptions();

      assertEquals(0, topicControl.getSubscriptionCount());

      connection_1.close();
      connection_2.close();
   }

   public void testRemoveAllMessages() throws Exception
   {
      Connection connection_1 = JMSUtil.createConnection(InVMConnectorFactory.class.getName());
      JMSUtil.createDurableSubscriber(connection_1, topic, clientID, subscriptionName);
      Connection connection_2 = JMSUtil.createConnection(InVMConnectorFactory.class.getName());
      JMSUtil.createDurableSubscriber(connection_2, topic, clientID, subscriptionName + "2");

      JMSUtil.sendMessages(topic, 3);

      TopicControl topicControl = createManagementControl();
      assertEquals(3 * 2, topicControl.getMessageCount());

      int removedCount = topicControl.removeMessages(null);
      assertEquals(3 * 2, removedCount);
      assertEquals(0, topicControl.getMessageCount());

      connection_1.close();
      connection_2.close();
   }

   public void testListMessagesForSubscription() throws Exception
   {
      Connection connection = JMSUtil.createConnection(InVMConnectorFactory.class.getName());

      JMSUtil.createDurableSubscriber(connection, topic, clientID, subscriptionName);

      JMSUtil.sendMessages(topic, 3);

      TopicControl topicControl = createManagementControl();
      Map<String, Object>[] messages = topicControl.listMessagesForSubscription(HornetQTopic.createQueueNameForDurableSubscription(clientID,
                                                                                                                                 subscriptionName));
      assertEquals(3, messages.length);
      
      connection.close();
   }

   public void testListMessagesForSubscriptionAsJSON() throws Exception
   {
      Connection connection = JMSUtil.createConnection(InVMConnectorFactory.class.getName());

      JMSUtil.createDurableSubscriber(connection, topic, clientID, subscriptionName);

      String[] ids = JMSUtil.sendMessages(topic, 3);

      TopicControl topicControl = createManagementControl();
      String jsonString = topicControl.listMessagesForSubscriptionAsJSON(HornetQTopic.createQueueNameForDurableSubscription(clientID,
                                                                                                                                 subscriptionName));
      assertNotNull(jsonString);
      JSONArray array = new JSONArray(jsonString);
      assertEquals(3, array.length());
      for (int i = 0; i < 3; i++)
      {
         assertEquals(ids[i], array.getJSONObject(i).get("JMSMessageID"));
      }
      
      connection.close();
   }

   public void testListMessagesForSubscriptionWithUnknownClientID() throws Exception
   {
      String unknownClientID = randomString();

      TopicControl topicControl = createManagementControl();

      try
      {
         topicControl.listMessagesForSubscription(HornetQTopic.createQueueNameForDurableSubscription(unknownClientID,
                                                                                                   subscriptionName));
         fail();
      }
      catch (Exception e)
      {
      }
   }

   public void testListMessagesForSubscriptionWithUnknownSubscription() throws Exception
   {
      String unknownSubscription = randomString();

      TopicControl topicControl = createManagementControl();

      try
      {
         topicControl.listMessagesForSubscription(HornetQTopic.createQueueNameForDurableSubscription(clientID,
                                                                                                   unknownSubscription));
         fail();
      }
      catch (Exception e)
      {
      }
   }

   // Package protected ---------------------------------------------

   // Protected -----------------------------------------------------

   @Override
   protected void setUp() throws Exception
   {
      super.setUp();

      Configuration conf = new ConfigurationImpl();
      conf.setSecurityEnabled(false);
      conf.setJMXManagementEnabled(true);
      conf.getAcceptorConfigurations()
          .add(new TransportConfiguration("org.hornetq.core.remoting.impl.invm.InVMAcceptorFactory"));
      server = HornetQ.newMessagingServer(conf, mbeanServer, false);
      server.start();

      serverManager = new JMSServerManagerImpl(server);
      serverManager.start();
      serverManager.setContext(new NullInitialContext());
      serverManager.activated();

      clientID = randomString();
      subscriptionName = randomString();

      String topicName = randomString();
      serverManager.createTopic(topicName, topicName);
      topic = new HornetQTopic(topicName);
   }

   @Override
   protected void tearDown() throws Exception
   {
      serverManager.stop();
      
      server.stop();

      serverManager = null;
      
      server = null;
      
      topic = null;

      super.tearDown();
   }

   protected TopicControl createManagementControl() throws Exception
   {
      return createTopicControl(topic, mbeanServer);
   }

   // Private -------------------------------------------------------

   // Inner classes -------------------------------------------------

}
