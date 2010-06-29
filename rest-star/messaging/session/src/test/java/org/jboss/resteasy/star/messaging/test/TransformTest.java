package org.jboss.resteasy.star.messaging.test;

import org.hornetq.api.core.client.ClientMessage;
import org.hornetq.api.core.client.ClientProducer;
import org.hornetq.api.core.client.ClientSession;
import org.hornetq.api.core.client.MessageHandler;
import org.jboss.resteasy.client.ClientRequest;
import org.jboss.resteasy.client.ClientResponse;
import org.jboss.resteasy.spi.Link;
import org.jboss.resteasy.star.messaging.Hornetq;
import org.jboss.resteasy.star.messaging.queue.QueueDeployer;
import org.jboss.resteasy.star.messaging.queue.QueueDeployment;
import org.jboss.resteasy.star.messaging.queue.QueueServerDeployer;
import org.jboss.resteasy.test.BaseResourceTest;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import javax.xml.bind.annotation.XmlRootElement;
import java.io.Serializable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.jboss.resteasy.test.TestPortProvider.*;

/**
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>
 * @version $Revision: 1 $
 */
public class TransformTest extends BaseResourceTest
{
   public static QueueDeployer server;

   @BeforeClass
   public static void setup() throws Exception
   {
      server = new QueueServerDeployer();
      server.setRegistry(deployment.getRegistry());
      server.start();
      QueueDeployment deployment = new QueueDeployment();
      deployment.setAutoAcknowledge(true);
      deployment.setDuplicatesAllowed(true);
      deployment.setDurableSend(false);
      deployment.setName("testQueue");
      server.deploy(deployment);
   }

   @AfterClass
   public static void shutdown() throws Exception
   {
      server.stop();
   }

   @XmlRootElement
   public static class Order implements Serializable
   {
      private String name;
      private String amount;

      public String getName()
      {
         return name;
      }

      public void setName(String name)
      {
         this.name = name;
      }

      public String getAmount()
      {
         return amount;
      }

      public void setAmount(String amount)
      {
         this.amount = amount;
      }

      @Override
      public boolean equals(Object o)
      {
         if (this == o) return true;
         if (o == null || getClass() != o.getClass()) return false;

         Order order = (Order) o;

         if (!amount.equals(order.amount)) return false;
         if (!name.equals(order.name)) return false;

         return true;
      }

      @Override
      public int hashCode()
      {
         int result = name.hashCode();
         result = 31 * result + amount.hashCode();
         return result;
      }
   }

   public static void publish(String destination, Serializable object, String contentType) throws Exception
   {
      ClientSession session = server.getSessionFactory().createSession();
      try
      {
         ClientProducer producer = session.createProducer(destination);
         ClientMessage message = session.createMessage(false);
         if (contentType == null)
         {
            Hornetq.setEntity(message, object);
         }
         else Hornetq.setEntity(message, object, contentType);
         producer.send(message);
         session.start();
      }
      finally
      {
         session.close();
      }
      Thread.sleep(10);

   }


   @Test
   public void testTransform() throws Exception
   {

      ClientRequest request = new ClientRequest(generateURL("/queues/testQueue"));

      ClientResponse response = request.head();
      Assert.assertEquals(200, response.getStatus());
      Link sender = response.getLinkHeader().getLinkByTitle("create");
      System.out.println("create: " + sender);
      Link consumeNext = response.getLinkHeader().getLinkByTitle("consume-next");
      System.out.println("consume-next: " + consumeNext);

      // test that Accept header is used to set content-type
      {
         Order order = new Order();
         order.setName("1");
         order.setAmount("$5.00");
         publish("testQueue", order, null);


         ClientResponse res = consumeNext.request().accept("application/xml").post(String.class);
         Assert.assertEquals(200, res.getStatus());
         Assert.assertEquals("application/xml", res.getHeaders().getFirst("Content-Type").toString().toLowerCase());
         Order order2 = (Order) res.getEntity(Order.class);
         Assert.assertEquals(order, order2);
         consumeNext = res.getLinkHeader().getLinkByTitle("consume-next");
         Assert.assertNotNull(consumeNext);
      }

      // test that Accept header is used to set content-type
      {
         Order order = new Order();
         order.setName("1");
         order.setAmount("$5.00");
         publish("testQueue", order, null);

         ClientResponse res = consumeNext.request().accept("application/json").post(String.class);
         Assert.assertEquals(200, res.getStatus());
         Assert.assertEquals("application/json", res.getHeaders().getFirst("Content-Type").toString().toLowerCase());
         Order order2 = (Order) res.getEntity(Order.class);
         Assert.assertEquals(order, order2);
         consumeNext = res.getLinkHeader().getLinkByTitle("consume-next");
         Assert.assertNotNull(consumeNext);
      }

      // test that message property is used to set content type
      {
         Order order = new Order();
         order.setName("2");
         order.setAmount("$15.00");
         publish("testQueue", order, "application/xml");

         ClientResponse res = consumeNext.request().post(String.class);
         Assert.assertEquals(200, res.getStatus());
         Assert.assertEquals("application/xml", res.getHeaders().getFirst("Content-Type").toString().toLowerCase());
         Order order2 = (Order) res.getEntity(Order.class);
         Assert.assertEquals(order, order2);
         consumeNext = res.getLinkHeader().getLinkByTitle("consume-next");
         Assert.assertNotNull(consumeNext);
      }
   }

   public static class Listener implements MessageHandler
   {
      public static Order order;
      public static CountDownLatch latch = new CountDownLatch(1);

      @Override
      public void onMessage(ClientMessage clientMessage)
      {
         System.out.println("onMessage!");
         try
         {
            order = Hornetq.getEntity(clientMessage, Order.class);
         }
         catch (Exception e)
         {
            e.printStackTrace();
         }
         latch.countDown();
      }
   }

   @Test
   public void testJmsConsumer() throws Exception
   {
      QueueDeployment deployment = new QueueDeployment();
      deployment.setAutoAcknowledge(true);
      deployment.setDuplicatesAllowed(true);
      deployment.setDurableSend(false);
      deployment.setName("testQueue2");
      server.deploy(deployment);
      ClientSession session = server.getSessionFactory().createSession();
      try
      {
         session.createConsumer("testQueue2").setMessageHandler(new Listener());
         session.start();

         Thread.sleep(10);

         ClientRequest request = new ClientRequest(generateURL("/queues/testQueue2"));

         ClientResponse response = request.head();
         Assert.assertEquals(200, response.getStatus());
         Link sender = response.getLinkHeader().getLinkByTitle("create");
         System.out.println("create: " + sender);
         Link consumeNext = response.getLinkHeader().getLinkByTitle("consume-next");
         System.out.println("consume-next: " + consumeNext);

         // test that Accept header is used to set content-type
         {
            Order order = new Order();
            order.setName("1");
            order.setAmount("$5.00");
            response = sender.request().body("application/xml", order).post();
            Assert.assertEquals(201, response.getStatus());

            Listener.latch.await(2, TimeUnit.SECONDS);
            Assert.assertNotNull(Listener.order);
            Assert.assertEquals(order, Listener.order);
         }

      }
      finally
      {
         session.close();
      }
   }

}
