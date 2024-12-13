package velox.api.layer1.simplified.demo;

import java.awt.Color;

import velox.api.layer1.annotations.Layer1ApiVersion;
import velox.api.layer1.annotations.Layer1ApiVersionValue;
import velox.api.layer1.annotations.Layer1SimpleAttachable;
import velox.api.layer1.annotations.Layer1StrategyName;
import velox.api.layer1.annotations.Layer1TradingStrategy;
import velox.api.layer1.data.ExecutionInfo;
import velox.api.layer1.data.InstrumentInfo;
import velox.api.layer1.data.OrderCancelParameters;
import velox.api.layer1.data.OrderInfo;
import velox.api.layer1.data.OrderInfoUpdate;
import velox.api.layer1.messages.indicators.Layer1ApiUserMessageModifyIndicator.GraphType;
import velox.api.layer1.simplified.Api;
import velox.api.layer1.simplified.BboListener;
import velox.api.layer1.simplified.CustomModule;
import velox.api.layer1.simplified.Indicator;
import velox.api.layer1.simplified.InitialState;
import velox.api.layer1.simplified.OrdersListener;
import velox.api.layer1.simplified.TradeDataListener;
import velox.api.layer1.simplified.IntervalListener;
import velox.api.layer1.simplified.IntervalAdapter;
import velox.api.layer1.simplified.Intervals;
import velox.api.layer1.data.TradeInfo;
import velox.api.layer1.simplified.*;
import java.awt.*;

@Layer1SimpleAttachable
@Layer1StrategyName("EMA")
@Layer1ApiVersion(Layer1ApiVersionValue.VERSION2)

public class Ema implements CustomModule, TradeDataListener, IntervalListener, HistoricalDataListener, BackfilledDataListener
{
    private Indicator emaIndicator;
    private double lastPrice = Double.NaN;
    private double emaValue = Double.NaN;

    @Parameter(name = "Alpha", step = 0.001, minimum = 0, maximum = 1)
    private Double alpha = 0.99;

    @Override
    public void initialize(String s, InstrumentInfo instrumentInfo, Api api, InitialState initialState)
    {
        emaIndicator = api.registerIndicator(("EMA 1"), GraphType.PRIMARY);
        emaIndicator.setColor(Color.green);
    }

    @Override
    public void stop()
    {}

    @Override
    public void onTrade(double price, int size, TradeInfo tradeInfo)
    {
        lastPrice = price;
    }

    @Override
    public long getInterval()
    {
        return Intervals.INTERVAL_1_SECOND;
    }

    @Override
    public void onInterval()
    {
        if (Double.isNaN(lastPrice))
        {
            return;
        }

        if (Double.isNaN(emaValue))
        {
            emaValue = lastPrice;
        }
        else emaValue = emaValue * alpha + lastPrice * (1 - alpha);

        emaIndicator.addPoint(emaValue);
    }
}
