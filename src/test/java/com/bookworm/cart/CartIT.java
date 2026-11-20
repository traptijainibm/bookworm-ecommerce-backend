package com.bookworm.cart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.web.servlet.MvcResult;

/**
 * End-to-end cart behaviour through the real security chain, JSON mapper, services and PostgreSQL
 * ({@code bookworm_test} only). Concurrency tests are milestone M6 and are not here.
 */
class CartIT extends AbstractCartIT {

    private static int statusOf(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private static String bodyOf(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    // ---- security through the full stack ------------------------------------------------------------------

    @Test
    void anonymousCallersGet401OnEveryCartRoute() throws Exception {
        mockMvc.perform(get("/api/v1/me/cart")).andExpect(status().isUnauthorized());
        mockMvc.perform(json(post("/api/v1/me/cart/items"), "{\"bookId\":1,\"quantity\":1}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(json(put("/api/v1/me/cart/items/1"), "{\"quantity\":1}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/v1/me/cart/items/1")).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/v1/me/cart/items")).andExpect(status().isUnauthorized());
    }

    @Test
    void anAdminGets403OnEveryCartRouteAndNothingIsCreated() throws Exception {
        long book = book("Admin Probe", "10.00", 5, true);

        mockMvc.perform(asAdmin(get("/api/v1/me/cart"))).andExpect(status().isForbidden());
        mockMvc.perform(asAdmin(json(post("/api/v1/me/cart/items"),
                "{\"bookId\":" + book + ",\"quantity\":1}"))).andExpect(status().isForbidden());
        mockMvc.perform(asAdmin(json(put("/api/v1/me/cart/items/" + book), "{\"quantity\":1}")))
                .andExpect(status().isForbidden());
        mockMvc.perform(asAdmin(delete("/api/v1/me/cart/items/" + book))).andExpect(status().isForbidden());
        mockMvc.perform(asAdmin(delete("/api/v1/me/cart/items"))).andExpect(status().isForbidden());

        assertThat(cartRows(adminId)).isZero();
    }

    @Test
    void aCustomerTokenClaimingAnAdminRoleIsStillACustomerAndAnAdminTokenClaimingCustomerIsStillAnAdmin()
            throws Exception {
        mockMvc.perform(bearer(get("/api/v1/me/cart"), token(customerId, "ADMIN"))).andExpect(status().isOk());
        mockMvc.perform(bearer(get("/api/v1/me/cart"), token(adminId, "CUSTOMER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void aCustomerDemotedToAdminLosesAccessOnTheNextRequest() throws Exception {
        mockMvc.perform(asCustomer(get("/api/v1/me/cart"))).andExpect(status().isOk());

        jdbc.update("update users set role = 'ADMIN' where id = ?", customerId);

        mockMvc.perform(asCustomer(get("/api/v1/me/cart"))).andExpect(status().isForbidden());
    }

    @Test
    void aDisabledOrDeletedCustomerIsRejectedOnTheNextRequest() throws Exception {
        jdbc.update("update users set enabled = false where id = ?", customerId);
        mockMvc.perform(asCustomer(get("/api/v1/me/cart"))).andExpect(status().isUnauthorized());

        jdbc.update("update users set enabled = true where id = ?", customerId);
        mockMvc.perform(asCustomer(get("/api/v1/me/cart"))).andExpect(status().isOk());

        jdbc.update("delete from users where id = ?", customerId);
        mockMvc.perform(asCustomer(get("/api/v1/me/cart"))).andExpect(status().isUnauthorized());
    }

    @Test
    void theProfileEndpointsStayOpenToAnyAuthenticatedUserIncludingAnAdmin() throws Exception {
        mockMvc.perform(asAdmin(get("/api/v1/me"))).andExpect(status().isOk());
        mockMvc.perform(asCustomer(get("/api/v1/me"))).andExpect(status().isOk());
    }

    // ---- reading ---------------------------------------------------------------------------------------------

    @Test
    void aCustomerWhoNeverAddedAnythingSeesAnEmptyCartAndGetCreatesNoRow() throws Exception {
        MvcResult result = getCart(customerToken);

        assertThat(statusOf(result)).isEqualTo(200);
        assertThat(read(result, "$.lineCount")).isEqualTo("0");
        assertThat(read(result, "$.itemCount")).isEqualTo("0");
        assertThat(new BigDecimal(read(result, "$.subtotal"))).isEqualByComparingTo("0.00");
        assertThat(read(result, "$.checkoutReady")).isEqualTo("false");
        assertThat(cartRows(customerId)).isZero();
    }

    @Test
    void aUserIdParameterNeverSelectsAnotherCustomersCart() throws Exception {
        long book = book("Private", "10.00", 5, true);
        assertThat(statusOf(addToCart(otherCustomerToken, book, 2))).isEqualTo(200);

        MvcResult result = mockMvc.perform(asCustomer(get("/api/v1/me/cart")
                .param("userId", String.valueOf(otherCustomerId)))).andReturn();

        assertThat(read(result, "$.lineCount")).isEqualTo("0");
    }

    // ---- adding ----------------------------------------------------------------------------------------------

    @Test
    void addingCreatesTheCartAndALineWithLivePricesAndExactTotals() throws Exception {
        long book = book("Dune", "199.99", 10, true);

        MvcResult result = addToCart(customerToken, book, 3);

        assertThat(statusOf(result)).isEqualTo(200);
        assertThat(read(result, "$.items[0].bookId")).isEqualTo(String.valueOf(book));
        assertThat(read(result, "$.items[0].title")).isEqualTo("Itcart Dune");
        assertThat(read(result, "$.items[0].availability")).isEqualTo("AVAILABLE");
        assertThat(read(result, "$.items[0].authors[0].name")).isEqualTo("Itcart Author");
        assertThat(new BigDecimal(read(result, "$.items[0].unitPrice"))).isEqualByComparingTo("199.99");
        assertThat(new BigDecimal(read(result, "$.items[0].lineTotal"))).isEqualByComparingTo("599.97");
        assertThat(new BigDecimal(read(result, "$.subtotal"))).isEqualByComparingTo("599.97");
        assertThat(read(result, "$.itemCount")).isEqualTo("3");
        assertThat(read(result, "$.checkoutReady")).isEqualTo("true");
        assertThat(cartRows(customerId)).isEqualTo(1);
        assertThat(quantityOf(customerId, book)).isEqualTo(3);
    }

    @Test
    void repeatedAddsKeepOneLineAndOneCartAndSumTheQuantity() throws Exception {
        long book = book("Merge", "10.00", 20, true);

        addToCart(customerToken, book, 2);
        addToCart(customerToken, book, 3);
        MvcResult result = addToCart(customerToken, book, 1);

        assertThat(read(result, "$.lineCount")).isEqualTo("1");
        assertThat(read(result, "$.items[0].quantity")).isEqualTo("6");
        assertThat(cartRows(customerId)).isEqualTo(1);
        assertThat(lineRows(customerId)).isEqualTo(1);
    }

    @Test
    void theCartPersistsAcrossSeparateSessions() throws Exception {
        long book = book("Persist", "10.00", 5, true);
        addToCart(customerToken, book, 2);

        MvcResult later = getCart(token(customerId, "CUSTOMER"));

        assertThat(read(later, "$.items[0].quantity")).isEqualTo("2");
    }

    @Test
    void aTenCopyLineIsAllowedAndTheEleventhCopyIsRefused() throws Exception {
        long book = book("Ten", "1.00", 50, true);

        assertThat(statusOf(addToCart(customerToken, book, 10))).isEqualTo(200);
        MvcResult over = addToCart(customerToken, book, 1);

        assertThat(statusOf(over)).isEqualTo(422);
        assertThat(read(over, "$.code")).isEqualTo("QUANTITY_LIMIT_EXCEEDED");
        assertThat(quantityOf(customerId, book)).isEqualTo(10);
    }

    @Test
    void aQuantityAboveStockIsRefusedWithoutRevealingTheStockOrChangingIt() throws Exception {
        long book = book("Scarce", "10.00", 3, true);
        long none = book("Gone", "10.00", 0, true);

        MvcResult over = addToCart(customerToken, book, 4);
        MvcResult zero = addToCart(customerToken, none, 1);

        assertThat(statusOf(over)).isEqualTo(409);
        assertThat(read(over, "$.code")).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(bodyOf(over)).doesNotContain("stock quantity").doesNotContain("\"3\"");
        assertThat(statusOf(zero)).isEqualTo(409);
        assertThat(read(zero, "$.code")).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(lineRows(customerId)).isZero();
        assertThat(stockOf(book)).isEqualTo(3);
        assertThat(stockOf(none)).isZero();
    }

    @Test
    void aRejectedFirstAddLeavesNoCartRowBehind() throws Exception {
        long book = book("Rollback", "10.00", 1, true);

        assertThat(statusOf(addToCart(customerToken, book, 2))).isEqualTo(409);
        assertThat(statusOf(addToCart(customerToken, 999_999_999L, 1))).isEqualTo(404);

        assertThat(cartRows(customerId)).isZero();
    }

    @Test
    void theFiftyFirstDistinctBookIsRefusedAndTheLinesAreUnchanged() throws Exception {
        for (int i = 1; i <= 50; i++) {
            long book = book("Many " + i, "1.00", 5, true);
            assertThat(statusOf(addToCart(customerToken, book, 1))).isEqualTo(200);
        }
        long fiftyFirst = book("Many 51", "1.00", 5, true);

        MvcResult full = addToCart(customerToken, fiftyFirst, 1);

        assertThat(statusOf(full)).isEqualTo(422);
        assertThat(read(full, "$.code")).isEqualTo("CART_FULL");
        assertThat(lineRows(customerId)).isEqualTo(50);
    }

    @Test
    void aFullCartStillMergesIntoAnExistingLine() throws Exception {
        long first = 0;
        for (int i = 1; i <= 50; i++) {
            long book = book("Full " + i, "1.00", 5, true);
            first = i == 1 ? book : first;
            addToCart(customerToken, book, 1);
        }

        MvcResult merged = addToCart(customerToken, first, 2);

        assertThat(statusOf(merged)).isEqualTo(200);
        assertThat(quantityOf(customerId, first)).isEqualTo(3);
        assertThat(lineRows(customerId)).isEqualTo(50);
    }

    // ---- inactive and unknown books --------------------------------------------------------------------------

    @Test
    void addingAnInactiveBookIsTheSame404AsAnUnknownBook() throws Exception {
        long inactive = book("Hidden", "10.00", 5, false);

        MvcResult hidden = addToCart(customerToken, inactive, 1);
        MvcResult unknown = addToCart(customerToken, 999_999_999L, 1);

        assertThat(statusOf(hidden)).isEqualTo(404);
        assertThat(statusOf(unknown)).isEqualTo(404);
        assertThat(read(hidden, "$.code")).isEqualTo("NOT_FOUND");
        assertThat(read(hidden, "$.detail")).isEqualTo(read(unknown, "$.detail"));
        assertThat(read(hidden, "$.title")).isEqualTo(read(unknown, "$.title"));
    }

    @Test
    void afterDeactivationTheLineIsKeptMinimalExcludedFromTotalsAndShownAgainOnReactivation() throws Exception {
        long keep = book("Keep", "10.00", 5, true);
        long drop = book("Drop", "99.00", 5, true);
        addToCart(customerToken, keep, 2);
        addToCart(customerToken, drop, 4);

        setBookActive(drop, false);
        MvcResult hidden = getCart(customerToken);
        MvcResult again = getCart(customerToken);

        assertThat(read(hidden, "$.lineCount")).isEqualTo("2");
        assertThat(read(hidden, "$.items[1].availability")).isEqualTo("UNAVAILABLE");
        assertThat(read(hidden, "$.items[1].title")).isEqualTo("Itcart Drop");
        String line = String.valueOf((Object) com.jayway.jsonpath.JsonPath.read(bodyOf(hidden), "$.items[1]"));
        assertThat(line).doesNotContain("unitPrice").doesNotContain("quantity").doesNotContain("isbn")
                .doesNotContain("authors").doesNotContain("coverUrl").doesNotContain("lineTotal");
        assertThat(read(hidden, "$.itemCount")).isEqualTo("2");
        assertThat(new BigDecimal(read(hidden, "$.subtotal"))).isEqualByComparingTo("20.00");
        assertThat(read(hidden, "$.checkoutReady")).isEqualTo("false");
        assertThat(lineRows(customerId)).isEqualTo(2);
        assertThat(bodyOf(again)).isEqualTo(bodyOf(hidden));

        setBookActive(drop, true);
        MvcResult shown = getCart(customerToken);

        assertThat(read(shown, "$.items[1].availability")).isEqualTo("AVAILABLE");
        assertThat(read(shown, "$.items[1].quantity")).isEqualTo("4");
        assertThat(new BigDecimal(read(shown, "$.subtotal"))).isEqualByComparingTo("416.00");
    }

    @Test
    void afterDeactivationAddIs404PutIs409AndDeleteSucceeds() throws Exception {
        long book = book("Deactivated", "10.00", 5, true);
        addToCart(customerToken, book, 2);
        setBookActive(book, false);

        MvcResult add = addToCart(customerToken, book, 1);
        MvcResult put = setQuantity(customerToken, book, 3);

        assertThat(statusOf(add)).isEqualTo(404);
        assertThat(read(add, "$.code")).isEqualTo("NOT_FOUND");
        assertThat(statusOf(put)).isEqualTo(409);
        assertThat(read(put, "$.code")).isEqualTo("BOOK_UNAVAILABLE");
        assertThat(quantityOf(customerId, book)).isEqualTo(2);

        MvcResult removed = removeLine(customerToken, book);

        assertThat(statusOf(removed)).isEqualTo(200);
        assertThat(lineRows(customerId)).isZero();
    }

    // ---- changing a quantity ----------------------------------------------------------------------------------

    @Test
    void putSetsTheAbsoluteQuantityAndIsIdempotent() throws Exception {
        long book = book("Absolute", "10.00", 9, true);
        addToCart(customerToken, book, 2);

        MvcResult first = setQuantity(customerToken, book, 7);
        MvcResult second = setQuantity(customerToken, book, 7);

        assertThat(statusOf(first)).isEqualTo(200);
        assertThat(read(second, "$.items[0].quantity")).isEqualTo("7");
        assertThat(quantityOf(customerId, book)).isEqualTo(7);
        assertThat(lineRows(customerId)).isEqualTo(1);
    }

    @Test
    void putAboveStockIs409AndChangesNothing() throws Exception {
        long book = book("PutStock", "10.00", 3, true);
        addToCart(customerToken, book, 2);

        MvcResult over = setQuantity(customerToken, book, 4);

        assertThat(statusOf(over)).isEqualTo(409);
        assertThat(read(over, "$.code")).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(quantityOf(customerId, book)).isEqualTo(2);
    }

    @Test
    void putOnAnAbsentLineIs404WhetherOrNotACartExists() throws Exception {
        long book = book("Absent", "10.00", 5, true);
        long other = book("Present", "10.00", 5, true);

        MvcResult noCart = setQuantity(customerToken, book, 1);
        addToCart(customerToken, other, 1);
        MvcResult noLine = setQuantity(customerToken, book, 1);

        assertThat(statusOf(noCart)).isEqualTo(404);
        assertThat(statusOf(noLine)).isEqualTo(404);
        assertThat(read(noLine, "$.code")).isEqualTo("NOT_FOUND");
        assertThat(lineRows(customerId)).isEqualTo(1);
    }

    @Test
    void putOnAnAbsentLineIs404EvenForAnInactiveBookSoNothingIsLearnedAboutTheCatalogue() throws Exception {
        long inactive = book("InactivePut", "10.00", 5, false);

        MvcResult result = setQuantity(customerToken, inactive, 1);

        assertThat(statusOf(result)).isEqualTo(404);
        assertThat(read(result, "$.code")).isEqualTo("NOT_FOUND");
    }

    // ---- removing ---------------------------------------------------------------------------------------------

    @Test
    void deleteOfALineReturnsTheUpdatedCartAndIsIdempotent() throws Exception {
        long a = book("RemoveA", "10.00", 5, true);
        long b = book("RemoveB", "20.00", 5, true);
        addToCart(customerToken, a, 1);
        addToCart(customerToken, b, 2);

        MvcResult first = removeLine(customerToken, a);
        MvcResult second = removeLine(customerToken, a);

        assertThat(statusOf(first)).isEqualTo(200);
        assertThat(read(first, "$.lineCount")).isEqualTo("1");
        assertThat(new BigDecimal(read(first, "$.subtotal"))).isEqualByComparingTo("40.00");
        assertThat(statusOf(second)).isEqualTo(200);
        assertThat(read(second, "$.lineCount")).isEqualTo("1");
    }

    @Test
    void deleteWithoutACartOrOfAnUnknownBookSucceedsWithAnEmptyCartAndCreatesNothing() throws Exception {
        MvcResult noCart = removeLine(customerToken, 999_999_999L);
        MvcResult cleared = clearCart(customerToken);

        assertThat(statusOf(noCart)).isEqualTo(200);
        assertThat(read(noCart, "$.lineCount")).isEqualTo("0");
        assertThat(statusOf(cleared)).isEqualTo(200);
        assertThat(cartRows(customerId)).isZero();
    }

    @Test
    void clearRemovesEveryLineKeepsTheCartRowAndIsIdempotent() throws Exception {
        addToCart(customerToken, book("ClearA", "10.00", 5, true), 1);
        addToCart(customerToken, book("ClearB", "10.00", 5, true), 1);

        MvcResult first = clearCart(customerToken);
        MvcResult second = clearCart(customerToken);

        assertThat(statusOf(first)).isEqualTo(200);
        assertThat(read(first, "$.lineCount")).isEqualTo("0");
        assertThat(statusOf(second)).isEqualTo(200);
        assertThat(lineRows(customerId)).isZero();
        assertThat(cartRows(customerId)).isEqualTo(1);
    }

    // ---- isolation between customers ---------------------------------------------------------------------------

    @Test
    void oneCustomerCanNeverSeeOrChangeAnothersCart() throws Exception {
        long book = book("Mine", "10.00", 9, true);
        addToCart(customerToken, book, 4);

        MvcResult theirView = getCart(otherCustomerToken);
        MvcResult theirPut = setQuantity(otherCustomerToken, book, 1);
        MvcResult theirDelete = removeLine(otherCustomerToken, book);
        MvcResult theirClear = clearCart(otherCustomerToken);

        assertThat(read(theirView, "$.lineCount")).isEqualTo("0");
        assertThat(statusOf(theirPut)).isEqualTo(404);
        assertThat(statusOf(theirDelete)).isEqualTo(200);
        assertThat(read(theirDelete, "$.lineCount")).isEqualTo("0");
        assertThat(statusOf(theirClear)).isEqualTo(200);
        assertThat(quantityOf(customerId, book)).isEqualTo(4);
        assertThat(cartRows(otherCustomerId)).isZero();
    }

    @Test
    void twoCustomersHoldingTheSameBookHaveIndependentLines() throws Exception {
        long book = book("Shared", "10.00", 9, true);

        addToCart(customerToken, book, 2);
        addToCart(otherCustomerToken, book, 5);

        assertThat(quantityOf(customerId, book)).isEqualTo(2);
        assertThat(quantityOf(otherCustomerId, book)).isEqualTo(5);
        assertThat(stockOf(book)).isEqualTo(9);
    }

    // ---- request validation through the real mapper --------------------------------------------------------------

    @Test
    void invalidBodiesAreRejectedWithTheRightCodeAndNothingIsWritten() throws Exception {
        long book = book("Validate", "10.00", 9, true);
        for (String body : List.of("{\"bookId\":" + book + ",\"quantity\":0}",
                "{\"bookId\":" + book + ",\"quantity\":11}", "{\"bookId\":" + book + ",\"quantity\":-1}",
                "{\"bookId\":0,\"quantity\":1}", "{\"quantity\":1}", "{\"bookId\":" + book + "}",
                "{\"bookId\":" + book + ",\"quantity\":null}")) {
            mockMvc.perform(asCustomer(json(post("/api/v1/me/cart/items"), body)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        for (String body : List.of("{\"bookId\":" + book + ",\"quantity\":\"2\"}",
                "{\"bookId\":" + book + ",\"quantity\":2.7}", "{\"bookId\":" + book + ",\"quantity\":2.0}",
                "{\"bookId\":" + book + ",\"quantity\":true}", "{\"bookId\":\"" + book + "\",\"quantity\":1}",
                "{\"bookId\":" + book + ",\"quantity\":1,\"price\":0.01}",
                "{\"bookId\":" + book + ",\"quantity\":1,\"userId\":" + otherCustomerId + "}",
                "{\"bookId\":" + book + ",\"quantity\":99999999999999999999}")) {
            mockMvc.perform(asCustomer(json(post("/api/v1/me/cart/items"), body)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        }
        assertThat(cartRows(customerId)).isZero();
        assertThat(cartRows(otherCustomerId)).isZero();
    }

    @Test
    void malformedPathIdsAre400AndUnsupportedMethodsAre405() throws Exception {
        for (String id : List.of("0", "-1", "abc", "1.5")) {
            mockMvc.perform(asCustomer(delete("/api/v1/me/cart/items/" + id))).andExpect(status().isBadRequest());
            mockMvc.perform(asCustomer(json(put("/api/v1/me/cart/items/" + id), "{\"quantity\":1}")))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(asCustomer(patch("/api/v1/me/cart/items/1"))).andExpect(status().isMethodNotAllowed());
    }

    // ---- stock, books and the database ----------------------------------------------------------------------------

    @Test
    void noCartOperationEverChangesStockOrAnyBookRow() throws Exception {
        long book = book("Untouched", "10.00", 6, true);
        String before = jdbc.queryForObject(
                "select stock_quantity || '|' || price || '|' || active || '|' || coalesce(updated_at::text, '') "
                        + "from books where id = ?", String.class, book);

        addToCart(customerToken, book, 3);
        setQuantity(customerToken, book, 5);
        addToCart(customerToken, book, 1);
        getCart(customerToken);
        removeLine(customerToken, book);
        clearCart(customerToken);

        String after = jdbc.queryForObject(
                "select stock_quantity || '|' || price || '|' || active || '|' || coalesce(updated_at::text, '') "
                        + "from books where id = ?", String.class, book);
        assertThat(after).isEqualTo(before);
    }

    @Test
    void theCartResponseNeverContainsAStockCountOrInternalField() throws Exception {
        long book = book("Fields", "10.00", 37, true);

        String body = bodyOf(addToCart(customerToken, book, 2));

        assertThat(body).doesNotContain("stockQuantity").doesNotContain("\"stock\"").doesNotContain("version")
                .doesNotContain("\"active\"").doesNotContain("userId").doesNotContain("cartId");
    }

    @Test
    void theDatabaseConstraintsRejectBadRows() {
        long book = book("Constraints", "10.00", 5, true);
        long cartId = jdbc.queryForObject("insert into carts (user_id) values (?) returning id", Long.class,
                customerId);

        assertThatThrownBy(() -> jdbc.update(
                "insert into cart_items (cart_id, book_id, quantity) values (?, ?, 0)", cartId, book))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "insert into cart_items (cart_id, book_id, quantity) values (?, ?, 11)", cartId, book))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("insert into cart_items (cart_id, book_id, quantity) values (?, ?, 1)", cartId, book);
        assertThatThrownBy(() -> jdbc.update(
                "insert into cart_items (cart_id, book_id, quantity) values (?, ?, 2)", cartId, book))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into carts (user_id) values (?)", customerId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingAUserCascadesItsCartAndLines() throws Exception {
        long book = book("Cascade", "10.00", 5, true);
        addToCart(customerToken, book, 2);
        assertThat(cartRows(customerId)).isEqualTo(1);

        jdbc.update("delete from users where id = ?", customerId);

        Integer carts = jdbc.queryForObject("select count(*) from carts where user_id = ?", Integer.class,
                customerId);
        Integer lines = jdbc.queryForObject("select count(*) from cart_items where book_id = ?", Integer.class, book);
        assertThat(carts).isZero();
        assertThat(lines).isZero();
    }

    // ---- API surface ----------------------------------------------------------------------------------------------

    @Test
    void theOpenApiDocumentListsExactlyTheDocumentedCartOperationsWithBearerAuth() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/me/cart'].get.security[0].bearerAuth").exists())
                .andExpect(jsonPath("$.paths['/api/v1/me/cart'].post").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/me/cart/items'].post.security[0].bearerAuth").exists())
                .andExpect(jsonPath("$.paths['/api/v1/me/cart/items'].delete.security[0].bearerAuth").exists())
                .andExpect(jsonPath("$.paths['/api/v1/me/cart/items'].put").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/me/cart/items/{bookId}'].put.security[0].bearerAuth")
                        .exists())
                .andExpect(jsonPath("$.paths['/api/v1/me/cart/items/{bookId}'].delete.security[0].bearerAuth")
                        .exists())
                .andExpect(jsonPath("$.paths['/api/v1/me/cart/items/{bookId}'].post").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/me/cart/items/{bookId}'].patch").doesNotExist());
    }
}
