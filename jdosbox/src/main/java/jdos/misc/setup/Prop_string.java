package jdos.misc.setup;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;

public class Prop_string extends Property {

    private static final Logger logger = System.getLogger(Prop_string.class.getName());

    public Prop_string(String _propname, int when, String _value) {
        super(_propname, when);
        default_value.set(_value);
        value.set(_value);
    }
    @Override
    public void SetValue(String str) {
        //suggested values always case insensitive.
    	//If there are none then it can be paths and such which are case sensitive
        if (!suggested_values.isEmpty()) str = str.toLowerCase();
        SetVal(new Value(str, Value.Etype.V_STRING), false, true);
    }
    @Override
    public boolean CheckValue(Value in, boolean warn) {
        if (suggested_values.isEmpty()) return true;
        for (Value v : suggested_values) {
            if (v.equals(in)) { //Match!
                return true;
            }
            if (v.toString().equals("%u")) {
                try {
                    if (Integer.parseInt(in.toString()) >= 0)
                        return true;
                } catch (Exception _) {
                }
            }
        }
        logger.log(Level.WARNING, "\""+in.toString()+"\" is not a valid value for variable: "+propname+".\nIt might now be reset it to default value: "+ default_value);
        return false;
    }
}
