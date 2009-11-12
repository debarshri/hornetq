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

package org.hornetq.tests.integration.journal;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.hornetq.core.asyncio.impl.AsynchronousFileImpl;
import org.hornetq.core.config.impl.ConfigurationImpl;
import org.hornetq.core.journal.LoaderCallback;
import org.hornetq.core.journal.PreparedTransactionInfo;
import org.hornetq.core.journal.RecordInfo;
import org.hornetq.core.journal.SequentialFileFactory;
import org.hornetq.core.journal.impl.AIOSequentialFileFactory;
import org.hornetq.core.journal.impl.JournalImpl;
import org.hornetq.core.journal.impl.NIOSequentialFileFactory;
import org.hornetq.tests.util.SpawnedVMSupport;
import org.hornetq.tests.util.UnitTestCase;

/**
 * 
 * This test spawns a remote VM, as we want to "crash" the VM right after the journal is filled with data
 * 
 * @author <a href="mailto:clebert.suconic@jboss.com">Clebert Suconic</a>
 *
 */
public class ValidateTransactionHealthTest extends UnitTestCase
{

   // Constants -----------------------------------------------------

   // Attributes ----------------------------------------------------

   private static final int OK = 10;

   // Static --------------------------------------------------------

   // Constructors --------------------------------------------------

   // Public --------------------------------------------------------

   public void testAIO() throws Exception
   {
      internalTest("aio", getTestDir(), 10000, 100, true, true, 1);
   }

   public void testAIOHugeTransaction() throws Exception
   {
      internalTest("aio", getTestDir(), 10000, 10000, true, true, 1);
   }

   public void testAIOMultiThread() throws Exception
   {
      internalTest("aio", getTestDir(), 1000, 100, true, true, 10);
   }

   public void testAIONonTransactionalNoExternalProcess() throws Exception
   {
      internalTest("aio", getTestDir(), 1000, 0, true, false, 10);
   }

   public void testNIO() throws Exception
   {
      internalTest("nio", getTestDir(), 10000, 100, true, true, 1);
   }

   public void testNIOHugeTransaction() throws Exception
   {
      internalTest("nio", getTestDir(), 10000, 10000, true, true, 1);
   }

   public void testNIOMultiThread() throws Exception
   {
      internalTest("nio", getTestDir(), 1000, 100, true, true, 10);
   }

   public void testNIONonTransactional() throws Exception
   {
      internalTest("nio", getTestDir(), 10000, 0, true, true, 1);
   }
   
   

   public void testNIO2() throws Exception
   {
      internalTest("nio2", getTestDir(), 10000, 100, true, true, 1);
   }

   public void testNIO2HugeTransaction() throws Exception
   {
      internalTest("nio2", getTestDir(), 10000, 10000, true, true, 1);
   }

   public void testNIO2MultiThread() throws Exception
   {
      internalTest("nio2", getTestDir(), 1000, 100, true, true, 10);
   }

   public void testNIO2NonTransactional() throws Exception
   {
      internalTest("nio2", getTestDir(), 10000, 0, true, true, 1);
   }



   // Package protected ---------------------------------------------

   // Protected -----------------------------------------------------

   @Override
   protected void setUp() throws Exception
   {
      super.setUp();
      
      File file = new File(getTestDir());
      deleteDirectory(file);
      file.mkdir();
   }

   // Private -------------------------------------------------------

   private void internalTest(final String type,
                             final String journalDir,
                             final long numberOfRecords,
                             final int transactionSize,
                             final boolean append,
                             final boolean externalProcess,
                             final int numberOfThreads) throws Exception
   {
      try
      {
         if (type.equals("aio") && !AsynchronousFileImpl.isLoaded())
         {
            // Using System.out as this output will go towards junit report
            System.out.println("AIO not found, test being ignored on this platform");
            return;
         }

         // This property could be set to false for debug purposes.
         if (append)
         {
            if (externalProcess)
            {
               Process process = SpawnedVMSupport.spawnVM(ValidateTransactionHealthTest.class.getCanonicalName(),
                                                          type,
                                                          journalDir,
                                                          Long.toString(numberOfRecords),
                                                          Integer.toString(transactionSize),
                                                          Integer.toString(numberOfThreads));
               process.waitFor();
               assertEquals(ValidateTransactionHealthTest.OK, process.exitValue());
            }
            else
            {
               JournalImpl journal = ValidateTransactionHealthTest.appendData(type,
                                                                      journalDir,
                                                                      numberOfRecords,
                                                                      transactionSize,
                                                                      numberOfThreads);
               journal.stop();
            }
         }

         reload(type, journalDir, numberOfRecords, numberOfThreads);
      }
      finally
      {
         File file = new File(journalDir);
         deleteDirectory(file);
      }
   }

   private void reload(final String type, final String journalDir, final long numberOfRecords, final int numberOfThreads) throws Exception
   {
      JournalImpl journal = ValidateTransactionHealthTest.createJournal(type, journalDir);

      journal.start();
      Loader loadTest = new Loader(numberOfRecords);
      journal.load(loadTest);
      assertEquals(numberOfRecords * numberOfThreads, loadTest.numberOfAdds);
      assertEquals(0, loadTest.numberOfPreparedTransactions);
      assertEquals(0, loadTest.numberOfUpdates);
      assertEquals(0, loadTest.numberOfDeletes);
      
      journal.stop();

      if (loadTest.ex != null)
      {
         throw loadTest.ex;
      }
   }

   // Inner classes -------------------------------------------------

   class Loader implements LoaderCallback
   {
      int numberOfPreparedTransactions = 0;

      int numberOfAdds = 0;

      int numberOfDeletes = 0;

      int numberOfUpdates = 0;

      long expectedRecords = 0;

      Exception ex = null;

      long lastID = 0;

      public Loader(final long expectedRecords)
      {
         this.expectedRecords = expectedRecords;
      }

      public void addPreparedTransaction(final PreparedTransactionInfo preparedTransaction)
      {
         numberOfPreparedTransactions++;

      }

      public void addRecord(final RecordInfo info)
      {
         if (info.id == lastID)
         {
            System.out.println("id = " + info.id + " last id = " + lastID);
         }

         ByteBuffer buffer = ByteBuffer.wrap(info.data);
         long recordValue = buffer.getLong();

         if (recordValue != info.id)
         {
            ex = new Exception("Content not as expected (" + recordValue + " != " + info.id + ")");

         }

         lastID = info.id;
         numberOfAdds++;

      }

      public void deleteRecord(final long id)
      {
         numberOfDeletes++;

      }

      public void updateRecord(final RecordInfo info)
      {
         numberOfUpdates++;

      }

      /* (non-Javadoc)
       * @see org.hornetq.core.journal.TransactionFailureCallback#failedTransaction(long, java.util.List, java.util.List)
       */
      public void failedTransaction(long transactionID, List<RecordInfo> records, List<RecordInfo> recordsToDelete)
      {
      }

   }
   
   
   // Remote part of the test =================================================================
   


   public static void main(String args[]) throws Exception
   {

      if (args.length != 5)
      {
         System.err.println("Use: java -cp <classpath> " + ValidateTransactionHealthTest.class.getCanonicalName() +
                            " aio|nio <journalDirectory> <NumberOfElements> <TransactionSize> <NumberOfThreads>");
         System.exit(-1);
      }
      System.out.println("Running");
      String journalType = args[0];
      String journalDir = args[1];
      long numberOfElements = Long.parseLong(args[2]);
      int transactionSize = Integer.parseInt(args[3]);
      int numberOfThreads = Integer.parseInt(args[4]);

      try
      {
         appendData(journalType, journalDir, numberOfElements, transactionSize, numberOfThreads);

      }
      catch (Exception e)
      {
         e.printStackTrace(System.out);
         System.exit(-1);
      }

      System.exit(OK);
   }

   public static JournalImpl appendData(String journalType,
                                        String journalDir,
                                        long numberOfElements,
                                        int transactionSize,
                                        int numberOfThreads) throws Exception
   {
      final JournalImpl journal = createJournal(journalType, journalDir);

      journal.start();
      journal.load(new LoaderCallback()
      {

         public void addPreparedTransaction(PreparedTransactionInfo preparedTransaction)
         {
         }

         public void addRecord(RecordInfo info)
         {
         }

         public void deleteRecord(long id)
         {
         }

         public void updateRecord(RecordInfo info)
         {
         }

         public void failedTransaction(long transactionID, List<RecordInfo> records, List<RecordInfo> recordsToDelete)
         {
         }
      });

      LocalThreads threads[] = new LocalThreads[numberOfThreads];
      final AtomicLong sequenceTransaction = new AtomicLong();

      for (int i = 0; i < numberOfThreads; i++)
      {
         threads[i] = new LocalThreads(journal, numberOfElements, transactionSize, sequenceTransaction);
         threads[i].start();
      }

      Exception e = null;
      for (LocalThreads t : threads)
      {
         t.join();

         if (t.e != null)
         {
            e = t.e;
         }
      }

      if (e != null)
      {
         throw e;
      }

      return journal;
   }

   public static JournalImpl createJournal(String journalType, String journalDir)
   {
      JournalImpl journal = new JournalImpl(10485760,
                                            2,
                                            0,
                                            0,
                                            getFactory(journalType, journalDir),
                                            "journaltst",
                                            "tst",
                                            500);
      return journal;
   }

   public static SequentialFileFactory getFactory(String factoryType, String directory)
   {
      if (factoryType.equals("aio"))
      {
         return new AIOSequentialFileFactory(directory,
                                             ConfigurationImpl.DEFAULT_JOURNAL_BUFFER_SIZE,
                                             ConfigurationImpl.DEFAULT_JOURNAL_BUFFER_TIMEOUT,
                                             ConfigurationImpl.DEFAULT_JOURNAL_FLUSH_SYNC,
                                             false);
      }
      else
      if (factoryType.equals("nio2"))
      {
         return new NIOSequentialFileFactory(directory, true);
      }
      else
      {
         return new NIOSequentialFileFactory(directory, false);
      }
   }

   static class LocalThreads extends Thread
   {
      final JournalImpl journal;

      final long numberOfElements;

      final int transactionSize;

      final AtomicLong nextID;

      Exception e;

      public LocalThreads(JournalImpl journal, long numberOfElements, int transactionSize, AtomicLong nextID)
      {
         super();
         this.journal = journal;
         this.numberOfElements = numberOfElements;
         this.transactionSize = transactionSize;
         this.nextID = nextID;
      }

      public void run()
      {
         try
         {
            int transactionCounter = 0;

            long transactionId = nextID.incrementAndGet();

            for (long i = 0; i < numberOfElements; i++)
            {

               long id = nextID.incrementAndGet();

               ByteBuffer buffer = ByteBuffer.allocate(512 * 3);
               buffer.putLong(id);

               if (transactionSize != 0)
               {
                  journal.appendAddRecordTransactional(transactionId, id, (byte)99, buffer.array());

                  if (++transactionCounter == transactionSize)
                  {
                     System.out.println("Commit transaction " + transactionId);
                     journal.appendCommitRecord(transactionId, true);
                     transactionCounter = 0;
                     transactionId = nextID.incrementAndGet();
                  }
               }
               else
               {
                  journal.appendAddRecord(id, (byte)99, buffer.array(), false);
               }
            }

            if (transactionCounter != 0)
            {
               journal.appendCommitRecord(transactionId, true);
            }

            if (transactionSize == 0)
            {
               journal.debugWait();
            }
         }
         catch (Exception e)
         {
            this.e = e;
         }

      }
   }

   

}
