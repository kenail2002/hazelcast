package com.hazelcast.spi.impl;

import com.hazelcast.spi.MigrationAwareService;
import com.hazelcast.spi.Operation;
import com.hazelcast.spi.PartitionMigrationEvent;
import com.hazelcast.spi.PartitionReplicationEvent;
import com.hazelcast.test.HazelcastParametersRunnerFactory;
import com.hazelcast.test.annotation.ParallelTest;
import com.hazelcast.test.annotation.QuickTest;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.Collection;

import static com.hazelcast.spi.impl.DelegatingMigrationAwareService.IN_FLIGHT_MIGRATION_STAMP;
import static com.hazelcast.spi.impl.DelegatingMigrationAwareService.PRIMARY_REPLICA_INDEX;
import static com.hazelcast.spi.impl.DelegatingMigrationAwareService.isPrimaryReplicaMigrationEvent;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Test count-tracking functionality of DelegatingMigrationAwareService
 */
@RunWith(Parameterized.class)
@Parameterized.UseParametersRunnerFactory(HazelcastParametersRunnerFactory.class)
@Category({QuickTest.class, ParallelTest.class})
public class DelegatingMigrationAwareServiceTest {

    @Parameterized.Parameter
    public MigrationAwareService wrappedMigrationAwareService;

    @Parameterized.Parameter(1)
    public PartitionMigrationEvent event;

    @Rule
    public ExpectedException expectedException = ExpectedException.none();

    private DelegatingMigrationAwareService delegatingMigrationAwareService;
    private int initialMigrationStamp;

    @Parameterized.Parameters(name = "{0}, replica: {1}")
    public static Collection<Object> parameters() {
        PartitionMigrationEvent promotionEvent = mock(PartitionMigrationEvent.class);
        when(promotionEvent.getNewReplicaIndex()).thenReturn(PRIMARY_REPLICA_INDEX);
        when(promotionEvent.getCurrentReplicaIndex()).thenReturn(1);
        when(promotionEvent.toString()).thenReturn("1 > 0");
        PartitionMigrationEvent demotionEvent = mock(PartitionMigrationEvent.class);
        when(demotionEvent.getNewReplicaIndex()).thenReturn(1);
        when(demotionEvent.getCurrentReplicaIndex()).thenReturn(PRIMARY_REPLICA_INDEX);
        when(demotionEvent.toString()).thenReturn("0 > 1");
        PartitionMigrationEvent backupsEvent = mock(PartitionMigrationEvent.class);
        when(backupsEvent.getNewReplicaIndex()).thenReturn(1);
        when(backupsEvent.getCurrentReplicaIndex()).thenReturn(2);
        when(backupsEvent.toString()).thenReturn("2 > 1");

        return Arrays.asList(new Object[] {
                new Object[] {new NoOpMigrationAwareService(), promotionEvent},
                new Object[] {new NoOpMigrationAwareService(), demotionEvent},
                new Object[] {new NoOpMigrationAwareService(), backupsEvent},
                new Object[] {new ExceptionThrowingMigrationAwareService(), promotionEvent},
                new Object[] {new ExceptionThrowingMigrationAwareService(), demotionEvent},
                new Object[] {new ExceptionThrowingMigrationAwareService(), backupsEvent},
        });
    }

    @Before
    public void setUp() throws Exception {
        // setup the counting migration aware service and execute 1 prepareReplicationOperation (which does not
        // affect the counter)
        delegatingMigrationAwareService = new DelegatingMigrationAwareService(wrappedMigrationAwareService);
        delegatingMigrationAwareService.prepareReplicationOperation(null);
        initialMigrationStamp = delegatingMigrationAwareService.getMigrationStamp();
        // also execute the first part of migration: beforeMigration
        try {
            delegatingMigrationAwareService.beforeMigration(event);
        }
        catch (RuntimeException e) {
            // we do not care whether the wrapped service throws an exception
        }
    }

    @Test
    public void beforeMigration() throws Exception {
        // when: delegatingMigrationAwareService.beforeMigration was invoked (in setUp method)
        // then: if event involves primary replica, stamp should change.
        if (isPrimaryReplicaMigrationEvent(event) ) {
            assertEquals(IN_FLIGHT_MIGRATION_STAMP, delegatingMigrationAwareService.getMigrationStamp());
            assertFalse(delegatingMigrationAwareService.validateMigrationStamp(IN_FLIGHT_MIGRATION_STAMP));
        } else {
            assertEquals(initialMigrationStamp, delegatingMigrationAwareService.getMigrationStamp());
            assertTrue(delegatingMigrationAwareService.validateMigrationStamp(initialMigrationStamp));
        }
    }

    @Test
    public void commitMigration() throws Exception {
        // when: before - commit migration methods have been executed
        try {
            delegatingMigrationAwareService.commitMigration(event);
        }
        catch (RuntimeException e) {
            // we do not care whether the wrapped service throws an exception
        }

        int currentMigrationStamp = delegatingMigrationAwareService.getMigrationStamp();
        // then: if event involves primary replica, stamp should change.
        if (isPrimaryReplicaMigrationEvent(event) ) {
            assertNotEquals(initialMigrationStamp, currentMigrationStamp);
        } else {
            assertEquals(initialMigrationStamp, currentMigrationStamp);
        }
        assertTrue(delegatingMigrationAwareService.validateMigrationStamp(currentMigrationStamp));
    }

    @Test
    public void rollbackMigration() throws Exception {
        // when: before - rollback migration methods have been executed
        try {
            delegatingMigrationAwareService.rollbackMigration(event);
        }
        catch (RuntimeException e) {
            // we do not care whether the wrapped service throws an exception
        }

        int currentMigrationStamp = delegatingMigrationAwareService.getMigrationStamp();
        // then: if event involves primary replica, stamp should change.
        if (isPrimaryReplicaMigrationEvent(event) ) {
            assertNotEquals(initialMigrationStamp, currentMigrationStamp);
        } else {
            assertEquals(initialMigrationStamp, currentMigrationStamp);
        }
        assertTrue(delegatingMigrationAwareService.validateMigrationStamp(currentMigrationStamp));
    }

    @Test
    public void commitMigration_invalidCount_throwsAssertionError() {
        // when: invalid sequence of beforeMigration, commitMigration, commitMigration is executed
        // and
        try {
            delegatingMigrationAwareService.commitMigration(event);
        }
        catch (RuntimeException e) {
            // we do not care whether the wrapped service throws an exception
        }

        // on second commitMigration, if event involves partition owner assertion error is thrown
        if (isPrimaryReplicaMigrationEvent(event) ) {
            expectedException.expect(AssertionError.class);
        }
        try {
            delegatingMigrationAwareService.commitMigration(event);
        }
        catch (RuntimeException e) {
            // we do not care whether the wrapped service throws an exception
        }
    }

    @Test
    public void rollbackMigration_invalidCount_throwsAssertionError() {
        // when: invalid sequence of beforeMigration, rollbackMigration, rollbackMigration is executed
        try {
            delegatingMigrationAwareService.rollbackMigration(event);
        }
        catch (RuntimeException e) {
            // we do not care whether the wrapped service throws an exception
        }

        // on second rollbackMigration, if event involves partition owner assertion error is thrown
        if (isPrimaryReplicaMigrationEvent(event) ) {
            expectedException.expect(AssertionError.class);
        }
        try {
            delegatingMigrationAwareService.rollbackMigration(event);
        }
        catch (RuntimeException e) {
            // we do not care whether the wrapped service throws an exception
        }
    }

    static class ExceptionThrowingMigrationAwareService implements MigrationAwareService {
        @Override
        public Operation prepareReplicationOperation(PartitionReplicationEvent event) {
            return null;
        }

        @Override
        public void beforeMigration(PartitionMigrationEvent event) {
            throw new RuntimeException("");
        }

        @Override
        public void commitMigration(PartitionMigrationEvent event) {
            throw new RuntimeException("");
        }

        @Override
        public void rollbackMigration(PartitionMigrationEvent event) {
            throw new RuntimeException("");
        }

        @Override
        public String toString() {
            return "ExceptionThrowingMigrationAwareService";
        }
    }


    static class NoOpMigrationAwareService implements MigrationAwareService {
        @Override
        public Operation prepareReplicationOperation(PartitionReplicationEvent event) {
            return null;
        }

        @Override
        public void beforeMigration(PartitionMigrationEvent event) {

        }

        @Override
        public void commitMigration(PartitionMigrationEvent event) {

        }

        @Override
        public void rollbackMigration(PartitionMigrationEvent event) {

        }

        @Override
        public String toString() {
            return "NoOpMigrationAwareService";
        }
    }
}
