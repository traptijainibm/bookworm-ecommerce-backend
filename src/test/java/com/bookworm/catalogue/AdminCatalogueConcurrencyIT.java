package com.bookworm.catalogue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Real concurrent requests against PostgreSQL (committed transactions, no test-level rollback). Each test releases
 * all threads at once with a latch, so the duplicate/stale checks and the database constraints are both exercised.
 */
class AdminCatalogueConcurrencyIT extends AbstractAdminIT {

    private static final int THREADS = 6;

    private List<MvcResult> runConcurrently(int count, java.util.function.IntFunction<Callable<MvcResult>> task)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        try {
            CountDownLatch ready = new CountDownLatch(count);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<MvcResult>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                Callable<MvcResult> work = task.apply(i);
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return work.call();
                }));
            }
            assertThat(ready.await(20, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<MvcResult> results = new ArrayList<>();
            for (Future<MvcResult> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static long count(List<MvcResult> results, int status) {
        return results.stream().filter(r -> r.getResponse().getStatus() == status).count();
    }

    private static List<Integer> statuses(List<MvcResult> results) {
        return results.stream().map(r -> r.getResponse().getStatus()).toList();
    }

    @Test
    void concurrentCreatesOfTheSameIsbnProduceExactlyOneBookAndOnlyConflictsOtherwise() throws Exception {
        long category = createCategory("Itadm Race Cat");
        long author = createAuthor("Itadm Race Author");
        String isbn = isbn(70);

        // Half the callers use the ISBN-10 spelling of the same book.
        List<MvcResult> results = runConcurrently(THREADS, i -> () -> mockMvc.perform(asAdmin(json(
                post("/api/v1/admin/books"),
                bookJson(i % 2 == 0 ? isbn : isbn10(isbn), "Itadm Race Book " + i, category, author)))).andReturn());

        assertThat(count(results, 201)).as(statuses(results).toString()).isEqualTo(1);
        assertThat(count(results, 409)).as(statuses(results).toString()).isEqualTo(THREADS - 1);
        assertThat(results).noneMatch(r -> r.getResponse().getStatus() >= 500);
        assertThat(results).filteredOn(r -> r.getResponse().getStatus() == 409)
                .allSatisfy(r -> assertThat(r.getResponse().getContentAsString()).contains("DUPLICATE_RESOURCE")
                        .doesNotContain("uk_books_isbn").doesNotContain("constraint"));
        assertThat(jdbc.queryForObject("select count(*) from books where isbn = ?", Integer.class, isbn))
                .isEqualTo(1);
    }

    @Test
    void concurrentUpdatesWithTheSameVersionLetExactlyOneWin() throws Exception {
        long category = createCategory("Itadm Upd Race Cat");
        long author = createAuthor("Itadm Upd Race Author");
        long book = createBook(isbn(71), "Itadm Upd Race", category, author);

        List<MvcResult> results = runConcurrently(THREADS, i -> () -> mockMvc.perform(
                adminPut("/api/v1/admin/books/" + book,
                        bookUpdateJson(0, isbn(71), "Itadm Upd Race Writer " + i, category, author))).andReturn());

        assertThat(count(results, 200)).as(statuses(results).toString()).isEqualTo(1);
        assertThat(count(results, 409)).as(statuses(results).toString()).isEqualTo(THREADS - 1);
        assertThat(results).filteredOn(r -> r.getResponse().getStatus() == 409)
                .allSatisfy(r -> assertThat(r.getResponse().getContentAsString()).contains("CONCURRENT_UPDATE"));
        assertThat(jdbc.queryForObject("select version from books where id = ?", Long.class, book)).isEqualTo(1L);
    }

    @Test
    void concurrentCategoriesWhoseNamesShareASlugGetDistinctSlugsWithoutServerErrors() throws Exception {
        String[] names = {"Itadm Slug Race", "Itadm Slug-Race", "Itadm Slug.Race", "Itadm Slug,Race",
                "Itadm Slug/Race", "Itadm Slug:Race"};

        List<MvcResult> results = runConcurrently(names.length, i -> () -> createCategoryResult(names[i]));

        assertThat(count(results, 201)).as(statuses(results).toString()).isEqualTo(names.length);
        List<String> slugs = new ArrayList<>();
        for (MvcResult result : results) {
            slugs.add(read(result, "$.slug"));
        }
        assertThat(slugs).doesNotHaveDuplicates().allMatch(s -> s.startsWith("itadm-slug-race"));
        assertThat(jdbc.queryForObject("select count(distinct slug) from categories where name like 'Itadm Slug%'",
                Integer.class)).isEqualTo(names.length);
    }

    @Test
    void concurrentCategoriesWithTheSameNameProduceOneCreateAndOnlyConflicts() throws Exception {
        List<MvcResult> results = runConcurrently(THREADS, i -> () -> createCategoryResult(
                i % 2 == 0 ? "Itadm Same Cat" : "ITADM same CAT"));

        assertThat(count(results, 201)).as(statuses(results).toString()).isEqualTo(1);
        assertThat(count(results, 409)).as(statuses(results).toString()).isEqualTo(THREADS - 1);
        assertThat(jdbc.queryForObject("select count(*) from categories where lower(name) = 'itadm same cat'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentAuthorsWithTheSameNameProduceOneCreateAndOnlyConflicts() throws Exception {
        List<MvcResult> results = runConcurrently(THREADS, i -> () -> mockMvc.perform(asAdmin(json(
                post("/api/v1/admin/authors"),
                "{\"name\":\"" + (i % 2 == 0 ? "Itadm Same Author" : "ITADM same AUTHOR") + "\"}"))).andReturn());

        assertThat(count(results, 201)).as(statuses(results).toString()).isEqualTo(1);
        assertThat(count(results, 409)).as(statuses(results).toString()).isEqualTo(THREADS - 1);
        assertThat(jdbc.queryForObject("select count(*) from authors where lower(name) = 'itadm same author'",
                Integer.class)).isEqualTo(1);
    }
}
