package source.data;

import com.binance.api.client.BinanceApiRestClient;
import com.binance.api.client.BinanceApiWebSocketClient;
import com.binance.api.client.BinanceApiClientFactory;
import com.binance.api.client.domain.market.AggTrade;
import com.binance.api.client.domain.market.OrderBook;
import com.binance.api.client.domain.market.OrderBookEntry;
import com.binance.api.client.impl.BinanceApiServiceGenerator;
import messaging.EventManager;
import websocket.BinanceApiWebSocketClientImplFast;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Gateway for receiving depth stream and agg trade prices.
 */
public class BinanceGateway {
    private static final String BIDS  = "BIDS";
    private static final String ASKS  = "ASKS";

    private long lastUpdateId;

    private LocalOrderBook depthCache;

    /**
     * Key is the aggregate trade id, and the value contains the aggregated trade data, which is
     * automatically updated whenever a new agg data stream event arrives.
     */
    private Map<Long, AggTrade> aggTradesCache;

    public BinanceGateway(String symbol) {
        initializeDepthCache(symbol);
        initializeAggTradesCache(symbol);
    }

    /**
     * Initializes the depth cache by using the REST API.
     */
    private void initializeDepthCache(String symbol) {
        BinanceApiClientFactory factory = BinanceApiClientFactory.newInstance();
        BinanceApiRestClient client = factory.newRestClient();
        OrderBook orderBook = client.getOrderBook(symbol.toUpperCase(), 10);

        this.depthCache = new LocalOrderBook();
        this.lastUpdateId = orderBook.getLastUpdateId();

        NavigableMap<BigDecimal, BigDecimal> asks = new TreeMap<>(Comparator.reverseOrder());
        for (OrderBookEntry ask : orderBook.getAsks()) {
            asks.put(new BigDecimal(ask.getPrice()), new BigDecimal(ask.getQty()));
        }
        depthCache.put(ASKS, asks);

        NavigableMap<BigDecimal, BigDecimal> bids = new TreeMap<>(Comparator.reverseOrder());
        for (OrderBookEntry bid : orderBook.getBids()) {
            bids.put(new BigDecimal(bid.getPrice()), new BigDecimal(bid.getQty()));
        }
        depthCache.put(BIDS, bids);
    }

    /**
     * Initializes the aggTrades cache by using the REST API.
     */
    private void initializeAggTradesCache(String symbol) {
        BinanceApiClientFactory factory = BinanceApiClientFactory.newInstance();
        BinanceApiRestClient client = factory.newRestClient();
        List<AggTrade> aggTrades = client.getAggTrades(symbol.toUpperCase());

        this.aggTradesCache = new HashMap<>();
        for (AggTrade aggTrade : aggTrades) {
            aggTradesCache.put(aggTrade.getAggregatedTradeId(), aggTrade);
        }
    }

    /**
     * Begins streaming of depth events.
     */
    void startDepthEventStreaming(String symbol, EventManager eventManager) {
        BinanceApiWebSocketClient client = new BinanceApiWebSocketClientImplFast(
                BinanceApiServiceGenerator.getSharedClient());

        client.onDepthEvent(symbol.toLowerCase(), response -> {
            if (response.getFinalUpdateId() > lastUpdateId) {
//                System.out.println(response);
                lastUpdateId = response.getFinalUpdateId();
                updateOrderBook(getAsks(), response.getAsks());
                updateOrderBook(getBids(), response.getBids());
//                printDepthCache();

                try {
                    eventManager.publish(depthCache);
                } catch (InterruptedException ex) {
                    ex.printStackTrace();
                }
            }
        });
    }

    /**
     * Begins streaming of agg trades events.
     */
    void startAggTradesEventStreaming(String symbol, EventManager eventManager) {
        BinanceApiClientFactory factory = BinanceApiClientFactory.newInstance();
        BinanceApiWebSocketClient client = factory.newWebSocketClient();

        client.onAggTradeEvent(symbol.toLowerCase(), response -> {
            Long aggregatedTradeId = response.getAggregatedTradeId();
            AggTrade updateAggTrade = aggTradesCache.get(aggregatedTradeId);
            if (updateAggTrade == null) {
                // new agg trade
                updateAggTrade = new AggTrade();
            }
            updateAggTrade.setAggregatedTradeId(aggregatedTradeId);
            updateAggTrade.setPrice(response.getPrice());
            updateAggTrade.setQuantity(response.getQuantity());
            updateAggTrade.setFirstBreakdownTradeId(response.getFirstBreakdownTradeId());
            updateAggTrade.setLastBreakdownTradeId(response.getLastBreakdownTradeId());
            updateAggTrade.setBuyerMaker(response.isBuyerMaker());

            // Store the updated agg trade in the cache
            aggTradesCache.put(aggregatedTradeId, updateAggTrade);
//            System.out.println(updateAggTrade);

            try {
                eventManager.publish(response);
            } catch (InterruptedException ex) {
                ex.printStackTrace();
            }
        });
    }

    /**
     * Updates an order book (bids or asks) with a delta received from the server.
     *
     * Whenever the qty specified is ZERO, it means the price should was removed from the order book.
     */
    private void updateOrderBook(NavigableMap<BigDecimal, BigDecimal> lastOrderBookEntries,
                                 List<OrderBookEntry> orderBookDeltas) {
        for (OrderBookEntry orderBookDelta : orderBookDeltas) {
            BigDecimal price = new BigDecimal(orderBookDelta.getPrice());
            BigDecimal qty = new BigDecimal(orderBookDelta.getQty());
            if (qty.compareTo(BigDecimal.ZERO) == 0) {
                // qty=0 means remove this level
                lastOrderBookEntries.remove(price);
            } else {
                lastOrderBookEntries.put(price, qty);
            }
        }
    }

    public NavigableMap<BigDecimal, BigDecimal> getAsks() {
        return depthCache.getAsks();
    }

    public NavigableMap<BigDecimal, BigDecimal> getBids() {
        return depthCache.getBids();
    }

    /**
     * @return the best ask in the order book
     */
    private Map.Entry<BigDecimal, BigDecimal> getBestAsk() {
        return getAsks().lastEntry();
    }

    /**
     * @return the best bid in the order book
     */
    private Map.Entry<BigDecimal, BigDecimal> getBestBid() {
        return getBids().firstEntry();
    }

    /**
     * @return a depth cache, containing two keys (ASKs and BIDs), and for each, an ordered list of book entries.
     */
    public LocalOrderBook getDepthCache() {
        return depthCache;
    }

    /**
     * @return an aggTrades cache, containing the aggregated trade id as the key,
     * and the agg trade data as the value.
     */
    public Map<Long, AggTrade> getAggTradesCache() {
        return aggTradesCache;
    }

    /**
     * Prints the cached order book / depth of a symbol as well as the best ask and bid price in the book.
     */
    private void printDepthCache() {
        System.out.println(depthCache);
        System.out.println("ASKS:");
        getAsks().entrySet().forEach(entry -> System.out.println(toDepthCacheEntryString(entry)));
        System.out.println("BIDS:");
        getBids().entrySet().forEach(entry -> System.out.println(toDepthCacheEntryString(entry)));
        System.out.println("BEST ASK: " + toDepthCacheEntryString(getBestAsk()));
        System.out.println("BEST BID: " + toDepthCacheEntryString(getBestBid()));
    }

    /**
     * Pretty prints an order book entry in the format "price / quantity".
     */
    private static String toDepthCacheEntryString(Map.Entry<BigDecimal, BigDecimal> depthCacheEntry) {
        return depthCacheEntry.getKey().toPlainString() + " / " + depthCacheEntry.getValue();
    }
}
