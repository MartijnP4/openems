package io.openems.edge.batteryinverter.deye;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(
    name = "BatteryInverter Deye SG04LP3",
    description = "BatteryInverter component for Deye SUN-10K SG04LP3-EU"
)
@interface BatteryInverterConfig {

    @AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
    String id() default "batteryInverter0";

    @AttributeDefinition(name = "Alias", description = "Human-readable name")
    String alias() default "";

    @AttributeDefinition(name = "Is enabled?")
    boolean enabled() default true;

    @AttributeDefinition(name = "Modbus-ID", description = "ID of Modbus bridge")
    String Modbus_target() default "(id=modbus0)";

    @AttributeDefinition(name = "Modbus Unit-ID", description = "Deye slave ID (default 1)")
    int modbusUnitId() default 1;
}
