package velox.api.layer1.simplified.demo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicBoolean;
import java.awt.Color;

import javax.swing.JLabel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import velox.api.layer1.messages.indicators.Layer1ApiUserMessageModifyIndicator.GraphType;
import velox.api.layer1.annotations.Layer1ApiVersion;
import velox.api.layer1.annotations.Layer1ApiVersionValue;
import velox.api.layer1.annotations.Layer1SimpleAttachable;
import velox.api.layer1.annotations.Layer1StrategyName;
import velox.api.layer1.annotations.Layer1TradingStrategy;
import velox.api.layer1.data.InstrumentInfo;
import velox.api.layer1.data.TradeInfo;
import velox.api.layer1.data.OrderSendParameters;
import velox.api.layer1.layers.utils.OrderBook;
import velox.api.layer1.layers.utils.mbo.Order;
import velox.api.layer1.layers.utils.mbo.OrderBookMbo;
import velox.api.layer1.simplified.Api;
import velox.api.layer1.simplified.AxisGroup;
import velox.api.layer1.simplified.AxisRules;
import velox.api.layer1.simplified.CustomModule;
import velox.api.layer1.simplified.CustomSettingsPanelProvider;
import velox.api.layer1.simplified.Indicator;
import velox.api.layer1.simplified.InitialState;
import velox.api.layer1.simplified.IntervalListener;
import velox.api.layer1.simplified.Intervals;
import velox.api.layer1.simplified.MarketByOrderDepthDataListener;
import velox.api.layer1.simplified.Parameter;
import velox.api.layer1.simplified.TradeDataListener;
import velox.gui.StrategyPanel;

import velox.api.layer1.data.OcoOrderSendParameters;
import velox.api.layer1.data.OrderCancelParameters;
import velox.api.layer1.data.OrderDuration;
import velox.api.layer1.data.OrderInfoBuilder;
import velox.api.layer1.data.OrderMoveParameters;
import velox.api.layer1.data.OrderResizeParameters;
import velox.api.layer1.data.OrderSendParameters;
import velox.api.layer1.data.OrderStatus;
import velox.api.layer1.data.OrderType;
import velox.api.layer1.data.OrderUpdateParameters;
import velox.api.layer1.data.SimpleOrderSendParameters;
import velox.api.layer1.data.SimpleOrderSendParametersBuilder;

/**
 * Visualizes MBO data
 */
@Layer1SimpleAttachable
@Layer1StrategyName("Drew MBO Total Calculator")
@Layer1ApiVersion(Layer1ApiVersionValue.VERSION2)
@Layer1TradingStrategy

// =====================================================================================================================
public class DrewMboTotalCalculator 
    implements CustomModule, CustomSettingsPanelProvider, MarketByOrderDepthDataListener, TradeDataListener, IntervalListener
{
    Api api;
    private String alias;
    private boolean hasOrderOpen = false;
    private boolean orderOpenLong = false;
    private OrderBookMbo    orderBookMbo    = new OrderBookMbo();
    private OrderBook       orderBook       = new OrderBook();
    private JLabel displayLabel;
    private AtomicBoolean updateIsScheduled = new AtomicBoolean();
    private double lastPrice;

    private Indicator strengthIndicator;
    private Indicator devIndicator;

    private double indicatorStrengthValue = 0;
    private double indicatorDevValue = 0;
    private Indicator zeroIndicator;
    
    AxisGroup grpAxis;
    AxisRules rulesAxisRules;

    @Parameter(name = "Max Ticks", step = 1, minimum = 1, maximum = 10000)
    private Integer maxTicks = 400;

    // -----------------------------------------------------------------------------------------------------------------
    public DrewMboTotalCalculator() 
    {
        SwingUtilities.invokeLater(() -> 
        {
            displayLabel = new JLabel();
        });
    }

    // -----------------------------------------------------------------------------------------------------------------
    @Override
    public void initialize(String alias, InstrumentInfo info, Api api, InitialState initialState) 
    {
        this.api = api;
        this.alias = alias;

        strengthIndicator = api.registerIndicator(("MBO Volume Strength"), GraphType.BOTTOM);
        strengthIndicator.setColor(Color.WHITE);

        devIndicator = api.registerIndicator(("MBO Dev Strength"), GraphType.BOTTOM);
        devIndicator.setColor(Color.LIGHT_GRAY);

        zeroIndicator = api.registerIndicator(("Zero Indicator"), GraphType.BOTTOM);
        zeroIndicator.setColor(Color.BLACK);

        grpAxis = new AxisGroup();
        grpAxis.add(strengthIndicator);
        grpAxis.add(zeroIndicator);
        grpAxis.add(devIndicator);
        
        rulesAxisRules= new AxisRules();
        rulesAxisRules.setSymmetrical(true);
        grpAxis.setAxisRules(rulesAxisRules); 
    }

    // -----------------------------------------------------------------------------------------------------------------
    @Override
    public void stop() 
    {}

    // -----------------------------------------------------------------------------------------------------------------
    @Override
    public void send(String orderId, boolean isBid, int price, int size) 
    {
        System.out.println("Order send");
        orderBookMbo.send(orderId, isBid, price, size);

        synchronized (orderBook) 
        {
            long levelSize = orderBook.getSizeFor(isBid, price, 0);
            levelSize += size;
            orderBook.onUpdate(isBid, price, levelSize);
        }

        scheduleUpdateIfNecessary();
    }

    // -----------------------------------------------------------------------------------------------------------------
    @Override
    public void replace(String orderId, int price, int size) 
    {
        System.out.println("Order replace");
        Order oldOrder = orderBookMbo.getOrder(orderId);
        boolean isBid = oldOrder.isBid();
        int oldPrice = oldOrder.getPrice();
        int oldSize  = oldOrder.getSize();

        orderBookMbo.replace(orderId, price, size);

        synchronized (orderBook) 
        {
            long oldLevelSize = orderBook.getSizeFor(isBid, oldPrice, 0);
            oldLevelSize -= oldSize;

            orderBook.onUpdate(isBid, oldPrice, oldLevelSize);

            long newLevelSize = orderBook.getSizeFor(isBid, price, 0);
            newLevelSize += size;
            orderBook.onUpdate(isBid, price, newLevelSize);
        }
        scheduleUpdateIfNecessary();
    }

    // -----------------------------------------------------------------------------------------------------------------
    @Override
    public void cancel(String orderId) 
    {
        System.out.println("Order cancel");
        Order oldOrder = orderBookMbo.getOrder(orderId);
        boolean isBid = oldOrder.isBid();
        int price = oldOrder.getPrice();
        int size  = oldOrder.getSize();

        orderBookMbo.cancel(orderId);

        synchronized (orderBook) 
        {
            long levelSize = orderBook.getSizeFor(isBid, price, 0);
            levelSize -= size;
            orderBook.onUpdate(isBid, price, levelSize);
        }
        scheduleUpdateIfNecessary();
    }

    // -----------------------------------------------------------------------------------------------------------------
    @Override
    public void onTrade(double price, int size, TradeInfo tradeInfo)
    {
        lastPrice = price;
    }

    // -----------------------------------------------------------------------------------------------------------------
    @Override
    public long getInterval()
    {
        return Intervals.INTERVAL_1_SECOND;
    }

    // -----------------------------------------------------------------------------------------------------------------
    @Override
    public void onInterval()
    {
        if (Double.isNaN(indicatorStrengthValue))
        {
            return;
        }
        devIndicator.addPoint(indicatorDevValue);
        strengthIndicator.addPoint(indicatorStrengthValue);
        zeroIndicator.addPoint(0.0);

        if ( hasOrderOpen == false)
        {
            if ( indicatorDevValue > 15 && indicatorStrengthValue > 110 && !hasOrderOpen)
            {
                SimpleOrderSendParametersBuilder longOrderSendParameters = new SimpleOrderSendParametersBuilder (alias, true, 10);
                //longOrderSendParameters.setLimitPrice(lastPrice-2);
                longOrderSendParameters.setBuy(true);
                OrderSendParameters longOrder = longOrderSendParameters.build();
                api.sendOrder(longOrder); 

                hasOrderOpen = true;
                orderOpenLong = true;
            }

            if ( indicatorDevValue < -15 && indicatorStrengthValue < -110 && !hasOrderOpen)
            {
                SimpleOrderSendParametersBuilder longOrderSendParameters = new SimpleOrderSendParametersBuilder (alias, false, 10);
                //longOrderSendParameters.setLimitPrice(lastPrice+2);
                longOrderSendParameters.setBuy(false);
                OrderSendParameters longOrder = longOrderSendParameters.build();
                api.sendOrder(longOrder); 

                hasOrderOpen = true;
                orderOpenLong = false;
            }
        }

        if ( hasOrderOpen )
        {
            if ( orderOpenLong )
            {
                if ( indicatorDevValue < 0 && indicatorStrengthValue < 50 )
                {
                    SimpleOrderSendParametersBuilder longOrderSendParameters = new SimpleOrderSendParametersBuilder (alias, false, 10);
                    longOrderSendParameters.setClosingPositionHint(true);
                    longOrderSendParameters.setBuy(false);
                    OrderSendParameters longOrder = longOrderSendParameters.build();
                    api.sendOrder(longOrder); 

                    hasOrderOpen = false;
                }
            }
            else if ( indicatorDevValue > 0 && indicatorStrengthValue > -50 && !orderOpenLong)
            {
                SimpleOrderSendParametersBuilder longOrderSendParameters = new SimpleOrderSendParametersBuilder (alias, true, 10);
                longOrderSendParameters.setClosingPositionHint(true);
                longOrderSendParameters.setBuy(true);
                OrderSendParameters longOrder = longOrderSendParameters.build();
                api.sendOrder(longOrder); 

                hasOrderOpen = false;
            }
        }
        
    }
   // -----------------------------------------------------------------------------------------------------------------
   private void scheduleUpdateIfNecessary() 
   {
        boolean shouldSchedule = !updateIsScheduled.getAndSet(true);

        if (shouldSchedule) 
        {
            SwingUtilities.invokeLater(() -> 
            {
                updateIsScheduled.set(false);

                StringBuilder builder = new StringBuilder();
                builder.append("<html>");

                synchronized (orderBook) 
                {
                    // Iterate over ask and bid orders to print order book data
                    Iterator<Entry<Integer, Long>> askIterator = orderBook.getAskMap().entrySet().iterator();
                    Iterator<Entry<Integer, Long>> bidIterator = orderBook.getBidMap().entrySet().iterator();

                    List<String> askRows = new ArrayList<>();
                    for (int i = 0; i < 10 && askIterator.hasNext(); ++i) 
                    {
                        Entry<Integer, Long> nextAskEntry = askIterator.next();
                        askRows.add("ASK Distance: " + i + " Price(int): " + nextAskEntry.getKey() + " Size: "
                                + nextAskEntry.getValue() + "<br/>");
                    }
                    Collections.reverse(askRows);
                    askRows.forEach(builder::append);

                    for (int i = 0; i < 10 && bidIterator.hasNext(); ++i) 
                    {
                        Entry<Integer, Long> nextBidEntry = bidIterator.next();
                        builder.append("BID Distance: " + i + " Price(int): " + nextBidEntry.getKey() + " Size: "
                                + nextBidEntry.getValue() + "<br/>");
                    }

                    // Assume tickSize and currentPrice are available. Get them from your context
                    int tickSize = 1;  // Example: Replace with the actual tick size
                    double currentPrice = lastPrice;  // Get the current price from the instrument info or order book

                    builder.append("<br/>Last Price: ").append(lastPrice).append("<br/>");

                    // Calculate bid strength
                    double bidStrength = calculateVolumeStrength(true, tickSize, currentPrice);
                    builder.append("Bid Strength: ").append(bidStrength).append("<br/>");

                    // Calculate ask strength
                    double askStrength = calculateVolumeStrength(false, tickSize, currentPrice);
                    builder.append("Ask Strength: ").append(askStrength).append("<br/>");
                    
                    double askDevStrength = calculateDevStrength(false, tickSize, currentPrice);
                    double bidDevStrength = calculateDevStrength(true, tickSize, currentPrice);

                    indicatorDevValue = (askDevStrength - bidDevStrength) * 100;
                    indicatorStrengthValue = (askStrength - bidStrength) * 100;
                }

                builder.append("</html>");
                displayLabel.setText(builder.toString());
            });
        }
    }

// -----------------------------------------------------------------------------------------------------------------
private double calculateVolumeStrength(boolean isBid, int tickSize, double currentPrice) 
{
    synchronized (orderBook) 
    {
        Iterator<Entry<Integer, Long>> iterator = isBid ?
            orderBook.getBidMap().entrySet().iterator() :
            orderBook.getAskMap().entrySet().iterator();

        int count = 0;
        double totalVolume = 0;
        List<Double> volumes = new ArrayList<>();

        // Define price range based on maxTicks and tickSize
        double lowerBound = isBid ? currentPrice - (maxTicks * tickSize) : currentPrice;
        double upperBound = isBid ? currentPrice : currentPrice + (maxTicks * tickSize);

        // Sum volumes and collect for standard deviation calculation
        while (iterator.hasNext() && count < maxTicks) 
        {
            Entry<Integer, Long> entry = iterator.next();
            int price = entry.getKey();
            double size = entry.getValue();

            // Filter orders within the defined price range
            if (price >= lowerBound && price <= upperBound) 
            {
                
                if ( size > 10 )
                {
                    totalVolume += size;
                    volumes.add(size);
                }
                count++;
            }
        }

        if (volumes.isEmpty()) 
        {
            return 0; // Handle case where no orders are within range
        }

        // Calculate mean and standard deviation
        double mean = totalVolume / volumes.size();
        double variance = volumes.stream()
            .mapToDouble(volume -> Math.pow(volume - mean, 2))
            .sum() / volumes.size();
        double stdDev = Math.sqrt(variance);

        // Return strength: total volume divided by the standard deviation
        // return stdDev == 0 ? 0 : totalVolume * 3 / stdDev;
        // return (totalVolume / (double)maxTicks) * (volumes.size() / (double)maxTicks);
        return totalVolume / maxTicks;
    }
}

private double calculateDevStrength(boolean isBid, int tickSize, double currentPrice) 
{
    synchronized (orderBook) 
    {
        Iterator<Entry<Integer, Long>> iterator = isBid ?
            orderBook.getBidMap().entrySet().iterator() :
            orderBook.getAskMap().entrySet().iterator();

        int count = 0;
        double totalVolume = 0;
        List<Double> volumes = new ArrayList<>();

        // Define price range based on maxTicks and tickSize
        double lowerBound = isBid ? currentPrice - (maxTicks * tickSize) : currentPrice;
        double upperBound = isBid ? currentPrice : currentPrice + (maxTicks * tickSize);

        // Sum volumes and collect for standard deviation calculation
        while (iterator.hasNext() && count < maxTicks) 
        {
            Entry<Integer, Long> entry = iterator.next();
            int price = entry.getKey();
            double size = entry.getValue();

            // Filter orders within the defined price range
            if (price >= lowerBound && price <= upperBound) 
            {
                
                if ( size > 10 )
                {
                    totalVolume += size;
                    volumes.add(size);
                }
                count++;
            }
        }

        if (volumes.isEmpty()) 
        {
            return 0; // Handle case where no orders are within range
        }

        // Calculate mean and standard deviation
        double mean = totalVolume / volumes.size();
        double variance = volumes.stream()
            .mapToDouble(volume -> Math.pow(volume - mean, 2))
            .sum() / volumes.size();
        double stdDev = Math.sqrt(variance);

        // Return strength: total volume divided by the standard deviation
        // return stdDev == 0 ? 0 : totalVolume * 3 / stdDev;
        return (totalVolume / (double)maxTicks) * (volumes.size() / (double)maxTicks);
        //return totalVolume / maxTicks;
    }
}



    // =====================================================================================================================
    // =====================================================================================================================
    // =====================================================================================================================
    @Override
    public StrategyPanel[] getCustomSettingsPanels() 
    {
        displayLabel = new JLabel();
        scheduleUpdateIfNecessary();

        // Create a settings panel for the order book display and other parameters
        StrategyPanel ordersPanel = new StrategyPanel("Order book");

        // Add the display label that will show the live updates of strengths and last price
        ordersPanel.add(displayLabel);
        
        // Add the editable parameter for Max Ticks to the panel
        StrategyPanel parameterPanel = new StrategyPanel("Parameters");
        // Create a SpinnerNumberModel with Integer for min/max values
        SpinnerNumberModel maxTicksModel = new SpinnerNumberModel(Integer.valueOf(maxTicks), Integer.valueOf(1), Integer.valueOf(10000), Integer.valueOf(1));
        JSpinner maxTicksSpinner = new JSpinner(maxTicksModel);

        // Add a change listener to update the maxTicks value when the spinner value changes
        maxTicksSpinner.addChangeListener(e -> maxTicks = (int) maxTicksSpinner.getValue());

        parameterPanel.add(maxTicksSpinner); // Add the spinner for maxTicks
        
        return new StrategyPanel[] { ordersPanel, parameterPanel };
    }
}
// =====================================================================================================================

