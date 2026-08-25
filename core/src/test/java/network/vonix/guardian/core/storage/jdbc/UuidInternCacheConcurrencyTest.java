package network.vonix.guardian.core.storage.jdbc;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class UuidInternCacheConcurrencyTest {

    @Test
    void concurrentUserCacheAdmissionNeverExceedsBound() throws Exception {
        ConcurrentHashMap<UUID, Integer> cache = new ConcurrentHashMap<>();
        int workers = 32;
        int values = 10_000;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        for (int worker = 0; worker < workers; worker++) {
            final int offset = worker;
            pool.submit(() -> {
                ready.countDown();
                start.await();
                for (int i = offset; i < values; i += workers) {
                    AbstractJdbcDao.cacheIfWithinBound(
                            cache, UUID.nameUUIDFromBytes(("user-" + i).getBytes()), i, 4096);
                }
                return null;
            });
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        assertThat(cache).hasSizeLessThanOrEqualTo(4096);
    }

    @Test
    void concurrentUniqueUuidAdmissionNeverExceedsBound() throws Exception {
        SqliteDao dao = new SqliteDao("jdbc:sqlite::memory:");
        Method safeUuid = AbstractJdbcDao.class.getDeclaredMethod("safeUuid", String.class);
        safeUuid.setAccessible(true);
        int workers = 32;
        int values = 10_000;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        for (int worker = 0; worker < workers; worker++) {
            final int offset = worker;
            pool.submit(() -> {
                ready.countDown();
                start.await();
                for (int i = offset; i < values; i += workers) {
                    safeUuid.invoke(dao, UUID.nameUUIDFromBytes(("uuid-" + i).getBytes()).toString());
                }
                return null;
            });
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        Field cacheField = AbstractJdbcDao.class.getDeclaredField("uuidIntern");
        cacheField.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, UUID> cache = (ConcurrentHashMap<String, UUID>) cacheField.get(dao);
        assertThat(cache).hasSizeLessThanOrEqualTo(4096);
    }
}
