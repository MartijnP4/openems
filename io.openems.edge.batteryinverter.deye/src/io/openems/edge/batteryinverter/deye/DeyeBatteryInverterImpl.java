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

import io.openems.common.channel.PersistencePriority;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.batteryinverter.api.ManagedSymmetricBatteryInverter;
import io.openems.edge.batteryinverter.api.SymmetricBatteryInverter;
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.element.SignedWordElement;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.bridge.modbus.api.task.FC16WriteRegistersTask;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.IntegerWriteChannel;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.taskmanager.Priority;

/**
 * Deye SUN-10K SG04LP3-EU — BatteryInverter Nature.
 *
 * <p>Reads power data and writes charge/discharge setpoints via Modbus TCP.
 * Uses validated register map from working Loxone installation.
 *
 * <p>Read registers:
 * 607 Grid Side Total Power int16 W (+ import, - export),
 * 636 Inverter Output Power uint16 W,
 * 142 Operating Mode uint16.
 *
 * <p>Write registers:
 * 108 Charge Limit uint16 A,
 * 109 Discharge Limit uint16 A,
 * 130 Grid Charge Enable uint16 (0=off, 1=on),
 * 142 Operating Mode uint16.
 *
 * <p>Conversion: amps = round((watts * 1000) / battery_voltage_v).
 * Example: 9000W at 48V = 187A.
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
                .text("Grid Side Total Power [W] (+ import, - export)")
                .persistencePriority(PersistencePriority.HIGH)),
        OPERATING_MODE(Doc.of(io.openems.common.types.OpenemsType.INTEGER)
                .text("Deye Operating Mode")
                .persistencePriority(PersistencePriority.MEDIUM)),
        SET_CHARGE_LIMIT_AMPERE(Doc.of(io.openems.common.types.OpenemsType.INTEGER)
                .text("Charge limit [A] written to register 108")
                .persistencePriority(PersistencePriority.MEDIUM)
                .accessMode(io.openems.common.channel.AccessMode.READ_WRITE)),
        SET_DISCHARGE_LIMIT_AMPERE(Doc.of(io.openems.common.types.OpenemsType.INTEGER)
                .text("Discharge limit [A] written to register 109")
                .persistencePriority(PersistencePriority.MEDIUM)
                .accessMode(io.openems.common.channel.AccessMode.READ_WRITE)),
        SET_GRID_CHARGE_ENABLE(Doc.of(io.openems.common.types.OpenemsType.INTEGER)
                .text("Grid Charge Enable: 0=off, 1=on")
                .persistencePriority(PersistencePriority.MEDIUM)
                .accessMode(io.openems.common.channel.AccessMode.READ_WRITE));

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
                config.modbusUnitId(), this.cm, "Modbus", config.modbus_id())) {
            return;
        }
    }

    @Deactivate
    protected void deactivate() {
        super.deactivate();
    }

    @Override
    protected ModbusProtocol defineModbusProtocol() {
        return new ModbusProtocol(this,
            // Operating Mode — register 142, uint16 (also writable)
            new FC3ReadRegistersTask(REG_OPERATING_MODE, Priority.LOW,
                m(ChannelId.OPERATING_MODE, new UnsignedWordElement(REG_OPERATING_MODE))
            ),
            // Grid Side Total Power — register 607, int16
            new FC3ReadRegistersTask(REG_GRID_POWER, Priority.HIGH,
                m(ChannelId.GRID_POWER, new SignedWordElement(REG_GRID_POWER))
            ),
            // Inverter Output Power — register 636, int16
            // Maps to ACTIVE_POWER (SymmetricBatteryInverter Nature channel)
            new FC3ReadRegistersTask(REG_INVERTER_POWER, Priority.HIGH,
                m(SymmetricBatteryInverter.ChannelId.ACTIVE_POWER,
                    new SignedWordElement(REG_INVERTER_POWER))
            ),
            // Write: Charge Limit — register 108, uint16 [A]
            new FC16WriteRegistersTask(REG_CHARGE_LIMIT,
                m(ChannelId.SET_CHARGE_LIMIT_AMPERE, new UnsignedWordElement(REG_CHARGE_LIMIT))
            ),
            // Write: Discharge Limit — register 109, uint16 [A]
            new FC16WriteRegistersTask(REG_DISCHARGE_LIMIT,
                m(ChannelId.SET_DISCHARGE_LIMIT_AMPERE, new UnsignedWordElement(REG_DISCHARGE_LIMIT))
            ),
            // Write: Grid Charge Enable — register 130, uint16
            new FC16WriteRegistersTask(REG_GRID_CHARGE_ENABLE,
                m(ChannelId.SET_GRID_CHARGE_ENABLE, new UnsignedWordElement(REG_GRID_CHARGE_ENABLE))
            )
        );
    }

    @Override
    public void setStartStop(io.openems.edge.common.startstop.StartStop value) {
        // Deye does not support explicit start/stop commands via Modbus
    }

    @Override
    public int getPowerPrecision() {
        // Deye werkt in Ampere stappen; bij 48V en 1A = 48W precisie
        return 48;
    }

    @Override
    public int getMaxApparentPower() {
        return MAX_POWER_W;
    }
            
    @Override
    public void run(io.openems.edge.battery.api.Battery battery, int setActivePower, int setReactivePower)
            throws OpenemsNamedException {

        // Convert W setpoint to A for registers 108/109
        int ampere = (int) Math.round(Math.abs((double) setActivePower * 1000.0 / BATTERY_VOLTAGE_V));
        int maxAmpere = MAX_POWER_W * 1000 / BATTERY_VOLTAGE_V;
        ampere = Math.min(ampere, maxAmpere);

        IntegerWriteChannel chargeChannel    = this.channel(ChannelId.SET_CHARGE_LIMIT_AMPERE);
        IntegerWriteChannel dischargeChannel = this.channel(ChannelId.SET_DISCHARGE_LIMIT_AMPERE);

        if (setActivePower < 0) {
            // Discharge: set discharge limit, zero charge limit
            dischargeChannel.setNextWriteValue(ampere);
            chargeChannel.setNextWriteValue(0);
        } else if (setActivePower > 0) {
            // Charge: set charge limit, zero discharge limit
            chargeChannel.setNextWriteValue(ampere);
            dischargeChannel.setNextWriteValue(0);
        } else {
            // Idle: zero both
            chargeChannel.setNextWriteValue(0);
            dischargeChannel.setNextWriteValue(0);
        }
    }

    @Override
    public String debugLog() {
        return "GridPwr:" + this.channel(ChannelId.GRID_POWER).value().asString() + "W"
            + "|InvPwr:" + this.getActivePower().asString() + "W"
            + "|Mode:" + this.channel(ChannelId.OPERATING_MODE).value().asString();
    }
}
