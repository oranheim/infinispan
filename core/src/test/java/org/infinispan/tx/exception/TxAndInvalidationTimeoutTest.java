/*
 * JBoss, Home of Professional Open Source
 * Copyright 2010 Red Hat Inc. and/or its affiliates and other
 * contributors as indicated by the @author tags. All rights reserved.
 * See the copyright.txt in the distribution for a full listing of
 * individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.infinispan.tx.exception;

import org.infinispan.commands.VisitableCommand;
import org.infinispan.commands.write.InvalidateCommand;
import org.infinispan.config.Configuration;
import org.infinispan.context.InvocationContext;
import org.infinispan.interceptors.base.CommandInterceptor;
import org.infinispan.test.MultipleCacheManagersTest;
import org.infinispan.test.TestingUtil;
import org.infinispan.transaction.TransactionTable;
import org.infinispan.transaction.lookup.DummyTransactionManagerLookup;
import org.infinispan.transaction.tm.DummyTransaction;
import org.infinispan.util.concurrent.TimeoutException;
import org.infinispan.util.concurrent.locks.LockManager;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;
import org.testng.annotations.Test;

import javax.transaction.HeuristicMixedException;
import javax.transaction.HeuristicRollbackException;
import javax.transaction.InvalidTransactionException;
import javax.transaction.NotSupportedException;
import javax.transaction.RollbackException;
import javax.transaction.SystemException;
import javax.transaction.Transaction;
import javax.transaction.TransactionManager;
import java.util.HashMap;
import java.util.Map;

import static org.testng.Assert.assertEquals;

/**
 *
 * @author Mircea.Markus@jboss.com
 * @since 4.2
 */
@Test (testName = "tx.timeout.TxAndInvalidationTimeoutTest", groups = "functional")
public class TxAndInvalidationTimeoutTest extends MultipleCacheManagersTest {

   private static final Log log = LogFactory.getLog(TxAndInvalidationTimeoutTest.class);

   private LockManager lm1;
   private LockManager lm0;
   private TransactionTable txTable0;
   private TransactionTable txTable1;
   private TransactionManager tm;
   private TxStatusInterceptor txStatus = new TxStatusInterceptor();

   @Override
   protected void createCacheManagers() throws Throwable {
      Configuration defaultConfig = getDefaultConfig();
      defaultConfig.fluent().transaction().transactionManagerLookup(new DummyTransactionManagerLookup());
      defaultConfig.setLockAcquisitionTimeout(500);
      defaultConfig.setUseLockStriping(false);
      addClusterEnabledCacheManager(defaultConfig);
      addClusterEnabledCacheManager(defaultConfig);
      lm0 = TestingUtil.extractLockManager(cache(0));
      lm1 = TestingUtil.extractLockManager(cache(1));
      txTable0 = TestingUtil.getTransactionTable(cache(0));
      txTable1 = TestingUtil.getTransactionTable(cache(1));
      tm = cache(0).getAdvancedCache().getTransactionManager();
      cache(1).getAdvancedCache().addInterceptor(txStatus, 0);
      TestingUtil.blockUntilViewReceived(cache(0), 2, 10000);
   }

   protected Configuration getDefaultConfig() {
      return getDefaultClusteredConfig(Configuration.CacheMode.INVALIDATION_SYNC, true);
   }

   public void testPutTimeoutsInTx() throws Exception {
      runAssertion(new CacheOperation() {
         @Override
         public void execute() {
            cache(0).put("k1", "v2222");
         }
      });
   }

   public void testRemoveTimeoutsInTx() throws Exception {
      runAssertion(new CacheOperation() {
         @Override
         public void execute() {
            cache(0).remove("k1");
         }
      });
   }

   public void testPutAllTimeoutsInTx() throws Exception {
      runAssertion(new CacheOperation() {
         @Override
         public void execute() {
            Map toAdd = new HashMap();
            toAdd.put("k1", "v22222");
            cache(0).putAll(toAdd);
         }
      });
   }


   private void runAssertion(CacheOperation operation) throws NotSupportedException, SystemException, HeuristicMixedException, HeuristicRollbackException, InvalidTransactionException, RollbackException {
      txStatus.reset();
      tm.begin();
      cache(1).put("k1", "v1");
      DummyTransaction k1LockOwner = (DummyTransaction) tm.getTransaction();
      k1LockOwner.runPrepare();
      tm.suspend();
      assert lm1.isLocked("k1");

      assertEquals(1, txTable1.getLocalTxCount());
      tm.begin();
      cache(0).put("k2", "v2");
      assert !lm0.isLocked("k2");
      assert !lm1.isLocked("k2");

      operation.execute();

      assertEquals(1, txTable1.getLocalTxCount());
      assertEquals(1, txTable0.getLocalTxCount());


      log.trace("Before commit");

      try {
         tm.commit();
         assert false;
      } catch (RollbackException re) {
         //expected
      }
      assert txStatus.teReceived;
      //expect 1 as k1 is locked by the other tx
      assertEquals(txStatus.numLocksAfterTeOnInvalidate, 1, "This would make sure that locks are being released quickly, not waiting for a remote rollback to happen");

      assertEquals(0, txTable0.getLocalTxCount());
      assertEquals(1, txTable1.getLocalTxCount());

      log.trace("Right before second commit");
      tm.resume(k1LockOwner);
      tm.commit();
      assertEquals(null, cache(0).get("k1"));
      assertEquals("v1", cache(1).get("k1"));
      assertEquals(0, txTable1.getLocalTxCount());
      assertEquals(0, txTable1.getLocalTxCount());
      assertEquals(0, lm0.getNumberOfLocksHeld());
      assertEquals(0, lm1.getNumberOfLocksHeld());
   }

   private class TxStatusInterceptor extends CommandInterceptor {

      private boolean teReceived;

      private int numLocksAfterTeOnInvalidate;

      @Override
      public Object visitInvalidateCommand(InvocationContext ctx, InvalidateCommand invalidateCommand) throws Throwable {
         try {
            return invokeNextInterceptor(ctx, invalidateCommand);
         } catch (TimeoutException te) {
            numLocksAfterTeOnInvalidate = lm1.getNumberOfLocksHeld();
            teReceived = true;
            throw te;
         }
      }

      @Override
      protected Object handleDefault(InvocationContext ctx, VisitableCommand command) throws Throwable {
         return super.handleDefault(ctx, command);
      }

      public void reset() {
         this.teReceived = false;
         this.numLocksAfterTeOnInvalidate = -1;
      }
   }

   public interface CacheOperation {

      public abstract void execute();
   }
}
