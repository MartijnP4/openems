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
import io.openems.edge.bridge.modbus.api.element.SignedWordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.task.FC16WriteRegistersTask;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.IntegerWriteChannel;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.startstop.StartStop;
import io.openems.edge.common.startstop.StartStoppable;
import io.openems.edge.common.taskmanager.Priority;

@Designate(ocd = BatteryInverterConfig.class, factory = true)
@Component(
    name = "BatteryInverter.Deye.SG04LP3",
    immediate = true,
    configurationPolicy = ConfigurationPolicy.REQUIRE
)
public class DeyeBatteryInverterImpl extends AbstractOpenemsModbusComponent
        implements ManagedSymmetricBatteryInverter, SymmetricBatteryInverter,
        ModbusComponent, OpenemsComponent, StartStoppable {

    private static final int REG_OPERATING_MODE = 142;
    private static final int REG_GRID_POWER = 607;
    private static final int REG_INVERTER_POWER = 636;
    private static final int REG_CHARGE_LIMIT = 108;
    private static final int REG_DISCHARGE_LIMIT = 109;
    private static final int REG_GRID_CHARGE_ENABLE = 130;
    private static final int BATTERY_VOLTAGE_V = 48;

    public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
        GRID_POWER(Doc.of(io.openems.common.types.OpenemsType.INTEGER)),
        INVERTER_OUTPUT_POWER(Doc.of(io.openems.common.types.OpenemsType.INTEGER)),
        OPERATING_MODE(Doc.of(io.openems.common.types.OpenemsType.INTEGER)),
        SET_CHARGE_LIMIT_AMPERE(Doc.of(io.openems.common.types.OpenemsType.INTEGER)),
        SET_DISCHARGE_LIMIT_AMPERE(Doc.of(io.openems.common.types.OpenemsType.INTEGER)),
        SET_GRID_CHARGE_ENABLE(Doc.of(io.openems.common.types.OpenemsType.INTEGER));

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
            StartStoppable.ChannelId.values(),
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
            new FC3ReadRegistersTask(REG_OPERATING_MODE, Priority.LOW,
                m(ChannelId.OPERATING_MODE, new UnsignedWordElement(REG_OPERATING_MODE))
            ),
            new FC3ReadRegistersTask(REG_GRID_POWER, Priority.HIGH,
                m(ChannelId.GRID_POWER, new SignedWordElement(REG_GRID_POWER))
            ),
            new FC3ReadRegistersTask(REG_INVERTER_POWER, Priority.HIGH,
                m(ChannelId.INVERTER_OUTPUT_POWER, new UnsignedWordElement(REG_INVERTER_POWER))
            ),
            new FC16WriteRegistersTask(REG_CHARGE_LIMIT,
                m(ChannelId.SET_CHARGE_LIMIT_AMPERE,
                    new UnsignedWordElement(REG_CHARGE_LIMIT)),
                m(ChannelId.SET_DISCHARGE_LIMIT_AMPERE,
                    new UnsignedWordElement(REG_DISCHARGE_LIMIT))
            ),
            new FC16WriteRegistersTask(REG_GRID_CHARGE_ENABLE,
                m(ChannelId.SET_GRID_CHARGE_ENABLE,
                    new UnsignedWordElement(REG_GRID_CHARGE_ENABLE))
            )
        );
    }

    private int wattsToAmps(int watts) {
        if (watts <= 0) {
            return 0;
        }
        return (int) Math.round((double) watts / BATTERY_VOLTAGE_V);
    }

    @Override
    public void run(Battery battery, int setActivePower, int setReactivePower)
            throws OpenemsNamedException {
        if (setActivePower >= 0) {
            this.<IntegerWriteChannel>channel(ChannelId.SET_CHARGE_LIMIT_AMPERE)
                .setNextWriteValue(this.wattsToAmps(setActivePower));
            this.<IntegerWriteChannel>channel(ChannelId.SET_DISCHARGE_LIMIT_AMPERE)
                .setNextWriteValue(0);
        } else {
            this.<IntegerWriteChannel>channel(ChannelId.SET_CHARGE_LIMIT_AMPERE)
                .setNextWriteValue(0);
            this.<IntegerWriteChannel>channel(ChannelId.SET_DISCHARGE_LIMIT_AMPERE)
                .setNextWriteValue(this.wattsToAmps(Math.abs(setActivePower)));
        }
    }

    @Override
    public int getPowerPrecision() {
        return BATTERY_VOLTAGE_V;
    }

    @Override
    public BatteryInverterConstraint[] getStaticConstraints()
            throws OpenemsNamedException {
        return BatteryInverterConstraint.NO_CONSTRAINTS;
    }

    @Override
    public void setStartStop(StartStop value) {
        // Start/stop is managed by the Deye inverter itself
    }

    @Override
    public String debugLog() {
        return "GridPwr:" + this.channel(ChannelId.GRID_POWER).value().asString()
            + "|InvPwr:" + this.channel(ChannelId.INVERTER_OUTPUT_POWER).value().asString();
    }

}
