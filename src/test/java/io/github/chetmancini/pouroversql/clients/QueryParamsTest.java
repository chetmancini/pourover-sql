package io.github.chetmancini.pouroversql.clients;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class QueryParamsTest {

    @Test
    public void testConstructor() {
        Integer fetchSize = 10;
        Integer maxRows = 100;
        Integer timeout = 1000;
        QueryParams queryParams = new QueryParams(fetchSize, maxRows, timeout);
        assertEquals(fetchSize, queryParams.getFetchSize());
        assertEquals(maxRows, queryParams.getMaxRows());
        assertEquals(timeout, queryParams.getTimeout());
    }

}
