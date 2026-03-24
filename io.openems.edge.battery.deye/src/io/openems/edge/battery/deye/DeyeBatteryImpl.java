package io.openems.edge.battery.deye;

import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.osgi.service.metatype.annotations.Designate;

import io.openems.edge.battery.api.Battery;
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.element.SignedWordElement;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.taskmanager.Priority;

import io.openems.edge.common.startstop.StartStop;
import io.openems.edge.common.startstop.StartStoppable;

/**
 * Deye SUN-10K SG04LP3-EU — Battery Nature
 * Reads SOC and battery power from the Deye inverter via Modbus TCP.
 * Uses validated register map from working Loxone installation.
 * Register map:
 *   588  Battery SOC        uint16  %
 *   590  Battery Power      int16   W  (+ = charging, - = discharging)
 *   500  Run State          uint16  0=standby, 2=normal
 */
@Designate(ocd = BatteryConfig.class, factory = true)
@Component(
    name = "Battery.Deye.SG04LP3",
    immediate = true,
    configurationPolicy = REQUIRE
)
public class DeyeBatteryImpl extends AbstractOpenemsModbusComponent
        implements Battery, ModbusComponent, OpenemsComponent, StartStoppable {

    // Register addresses
    private static final int REG_RUN_STATE   = 500;
    private static final int REG_BATTERY_SOC = 588;
    private static final int REG_BATTERY_PWR = 590;

    public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
        RUN_STATE(Doc.of(io.openems.common.types.OpenemsType.INTEGER)
                .text("Run State: 0=Standby, 2=Normal")),
        BATTERY_POWER_RAW(Doc.of(io.openems.common.types.OpenemsType.INTEGER)
                .text("Battery Power raw int16 [W]"));

        private final Doc doc;

        ChannelId(Doc doc) {
            this.doc = doc;
        }

        @Override
        public Doc doc() {
            return this.doc;
        }
    }

    @Reference
    private ConfigurationAdmin cm;

    public DeyeBatteryImpl() {
        super(
            OpenemsComponent.ChannelId.values(),
            ModbusComponent.ChannelId.values(),
            Battery.ChannelId.values(),
            ChannelId.values()
        );
    }

    @Reference(
        policy = ReferencePolicy.STATIC,
        policyOption = ReferencePolicyOption.GREEDY,
        cardinality = ReferenceCardinality.MANDATORY
    )
    protected void setModbus(BridgeModbus modbus) {
        super.setModbus(modbus);
    }

    @Activate
    void activate(ComponentContext context, BatteryConfig config) throws Exception {
        if (super.activate(context, config.id(), config.alias(), config.enabled(),
                config.modbusUnitId(), this.cm, "Modbus", config.Modbus_target())) {
            return;
        }
    }

    @Override
    @Deactivate
    protected void deactivate() {
        super.deactivate();
    }

    @Override
    protected ModbusProtocol defineModbusProtocol() {
        return new ModbusProtocol(this,

            // Run State — register 500
            new FC3ReadRegistersTask(REG_RUN_STATE, Priority.LOW,
                m(ChannelId.RUN_STATE, new UnsignedWordElement(REG_RUN_STATE))
            ),

            // Battery SOC — register 588 (uint16, %)
            // Battery Power — register 590 (int16, W)
            // Register 589 is skipped (dummy)
            new FC3ReadRegistersTask(REG_BATTERY_SOC, Priority.HIGH,
                m(Battery.ChannelId.SOC, new UnsignedWordElement(REG_BATTERY_SOC)),
                new io.openems.edge.bridge.modbus.api.element.DummyRegisterElement(589),
                m(ChannelId.BATTERY_POWER_RAW, new SignedWordElement(REG_BATTERY_PWR))
            )
        );
    }

    @Override
    public String debugLog() {
        return "SOC:" + this.getSoc().asString()
            + "|RunState:" + this.channel(ChannelId.RUN_STATE).value().asString();
    }
    
    @Override
    public void setStartStop(StartStop value) {
        // Start/stop is managed by the Deye inverter itself
    }

}
