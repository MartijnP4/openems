package io.openems.edge.batteryinverter.deye;

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

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.battery.api.Battery;
import io.openems.edge.batteryinverter.api.BatteryInverterConstraint;
import io.openems.edge.batteryinverter.api.ManagedSymmetricBatteryInverter;
import io.openems.edge.batteryinverter.api.SymmetricBatteryInverter;
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.DummyRegisterElement;
import io.openems.edge.bridge.modbus.api.element.SignedWordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.bridge.modbus.api.task.FC16WriteRegistersTask;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.IntegerWriteChannel;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.ess.power.api.Phase;
import io.openems.edge.ess.power.api.Pwr;
import io.openems.edge.ess.power.api.Relationship;

/**
 * Deye SUN-10K SG04LP3-EU — BatteryInverter Nature
 *
 * Reads grid/inverter power and writes charge/discharge setpoints.
 * Uses validated register map from working Loxone installation.
 *
 * Read registers:
 *   607  Grid Side Total Power    int16  W  (+ = import, - = export)
 *   636  Inverter Output Power    uint16 W
 *   142  Operating Mode           uint16
 *
 * Write registers:
 *   108  Charge Limit             uint16 A
 *   109  Discharge Limit          uint16 A
 *   130  Grid Charge Enable       uint16 0=off, 1=on
 *   142  Operating Mode           uint16
 *
 * Conversion: amps = round((watts * 1000) / battery_voltage_v)
 * Example: 9000W at 48V = 187A
 */
@Designate(ocd = BatteryInverterConfig.class, factory = true)
@Component(
    name = "BatteryInverter.Deye.SG04LP3",
    immediate = true,
    configurationPolicy = ConfigurationPolicy.REQUIRE
)
public class DeyeBatteryInverterImpl extends AbstractOpenemsModbusComponent
        implements ManagedSymmetricBatteryInverter, SymmetricBatteryInverter,
        ModbusComponent, OpenemsComponent {

    // Read register addresses
    private static final int REG_OPERATING_MODE      = 142;
    private static final int REG_GRID_POWER          = 607;
    private static final int REG_INVERTER_POWER      = 636;

    // Write register addresses
    private static final int REG_CHARGE_LIMIT        = 108;
    private static final int REG_DISCHARGE_LIMIT     = 109;
    private static final int REG_GRID_CHARGE_ENABLE  = 130;

    // Battery nominal voltage for W → A conversion
    private static final int BATTERY_VOLTAGE_V = 48;

    // Max inverter power in W
    private static final int MAX_POWER_W = 10000;

    public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
        GRID_POWER(Doc.of(io.openems.common.types.OpenemsType.INTEGER)
                .text("Grid Side Total Power [W] (+ import, - export)")),
        INVERTER_OUTPUT_POWER(Doc.of(io.openems.common.types.OpenemsType.INTEGER)
                .text("Inverter Output Total Power [W]")),
        OPERATING_MODE(Doc.of(io.openems.common.types.OpenemsType.INTEGER)
                .text("Deye Operating Mode")),
        SET_CHARGE_LIMIT_AMPERE(Doc.of(io.openems.common.types.OpenemsType.INTEGER)
                .text("Charge limit [A] written to register 108")),
        SET_DISCHARGE_LIMIT_AMPERE(Doc.of(io.openems.common.types.OpenemsType.INTEGER)
                .text("Discharge limit [A] written to register 109")),
        SET_GRID_CHARGE_ENABLE(Doc.of(io.openems.common.types.OpenemsType.INTEGER)
                .text("Grid Charge Enable: 0=off, 1=on"));

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

    public DeyeBatteryInverterImpl() {
        super(
            OpenemsComponent.ChannelId.values(),
            ModbusComponent.ChannelId.values(),
            SymmetricBatteryInverter.ChannelId.values(),
            ManagedSymmetricBatteryInverter.ChannelId.values(),
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
    void activate(ComponentContext context, BatteryInverterConfig config) throws Exception {
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

            // Read: Operating Mode (register 142)
            new FC3ReadRegistersTask(REG_OPERATING_MODE, Priority.LOW,
                m(ChannelId.OPERATING_MODE, new UnsignedWordElement(REG_OPERATING_MODE))
            ),

            // Read: Grid power (register 607) and Inverter output (register 636)
            new FC3ReadRegistersTask(REG_GRID_POWER, Priority.HIGH,
                m(ChannelId.GRID_POWER, new SignedWordElement(REG_GRID_POWER))
            ),
            new FC3ReadRegistersTask(REG_INVERTER_POWER, Priority.HIGH,
                m(ChannelId.INVERTER_OUTPUT_POWER, new UnsignedWordElement(REG_INVERTER_POWER))
            ),

            // Write: Charge limit (108) and Discharge limit (109)
            new FC16WriteRegistersTask(REG_CHARGE_LIMIT,
                m(ChannelId.SET_CHARGE_LIMIT_AMPERE,
                    new UnsignedWordElement(REG_CHARGE_LIMIT)),
                m(ChannelId.SET_DISCHARGE_LIMIT_AMPERE,
                    new UnsignedWordElement(REG_DISCHARGE_LIMIT))
            ),

            // Write: Grid Charge Enable (130)
            new FC16WriteRegistersTask(REG_GRID_CHARGE_ENABLE,
                m(ChannelId.SET_GRID_CHARGE_ENABLE,
                    new UnsignedWordElement(REG_GRID_CHARGE_ENABLE))
            )
        );
    }

    /**
     * Convert watts to amperes for Deye charge/discharge limit registers.
     * Formula: amps = round((watts * 1000) / battery_voltage)
     * But watts here is already in W, so: amps = round(watts / voltage)
     */
    private int wattsToAmps(int watts) {
        if (watts <= 0) return 0;
        return (int) Math.round((double) watts / BATTERY_VOLTAGE_V);
    }

    @Override
    public void run(Battery battery, int setActivePower, int setReactivePower)
            throws OpenemsNamedException {

        // Convert setActivePower (W) to Ampere for Deye registers
        // Positive = charge, negative = discharge
        if (setActivePower >= 0) {
            // Charging
            int chargeAmps = wattsToAmps(setActivePower);
            int dischargeAmps = 0;
            this.<IntegerWriteChannel>channel(ChannelId.SET_CHARGE_LIMIT_AMPERE)
                .setNextWriteValue(chargeAmps);
            this.<IntegerWriteChannel>channel(ChannelId.SET_DISCHARGE_LIMIT_AMPERE)
                .setNextWriteValue(dischargeAmps);
        } else {
            // Discharging
            int chargeAmps = 0;
            int dischargeAmps = wattsToAmps(Math.abs(setActivePower));
            this.<IntegerWriteChannel>channel(ChannelId.SET_CHARGE_LIMIT_AMPERE)
                .setNextWriteValue(chargeAmps);
            this.<IntegerWriteChannel>channel(ChannelId.SET_DISCHARGE_LIMIT_AMPERE)
                .setNextWriteValue(dischargeAmps);
        }
    }

    @Override
    public BatteryInverterConstraint[] getStaticConstraints()
            throws OpenemsNamedException {
        return new BatteryInverterConstraint[] {
            new BatteryInverterConstraint("Deye max power",
                Phase.ALL, Pwr.ACTIVE, Relationship.LESS_OR_EQUALS, MAX_POWER_W),
            new BatteryInverterConstraint("Deye min power",
                Phase.ALL, Pwr.ACTIVE, Relationship.GREATER_OR_EQUALS, -MAX_POWER_W)
        };
    }

    @Override
    public String debugLog() {
        return "GridPwr:" + this.channel(ChannelId.GRID_POWER).value().asString()
            + "|InvPwr:" + this.channel(ChannelId.INVERTER_OUTPUT_POWER).value().asString();
    }
}
