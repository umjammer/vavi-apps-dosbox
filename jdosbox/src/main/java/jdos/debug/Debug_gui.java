package jdos.debug;

import java.io.FileOutputStream;
import java.io.OutputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.HashMap;
import java.util.Map;

import jdos.misc.setup.Section;
import jdos.misc.setup.Section_prop;


public class Debug_gui {

    private static final Logger logger = System.getLogger(Debug_gui.class.getName());

    static class _LogGroup {

        String front;
        boolean enabled;
    }

    static final Map<String, _LogGroup> loggrp = new HashMap<>();
    static OutputStream debuglog;

    static final Section.SectionFunction LOG_Destroy = new Section.SectionFunction() {
        @Override
        public void call(Section section) {
            if (debuglog != null) {
                try {
                    debuglog.close();
                } catch (Exception ignore) {
                }
            }
        }
    };
    static Section.SectionFunction LOG_Init = new Section.SectionFunction() {
        @Override
        public void call(Section section) {
            Section_prop sect = (Section_prop) section;
            String blah = sect.Get_string("logfile");
            if (blah != null && !blah.isEmpty()) {
                try {
                    debuglog = new FileOutputStream(blah);
                } catch (Exception e) {
                    logger.log(Level.ERROR, e.getMessage(), e);
                }
            }
            sect.addDestroyFunction(LOG_Destroy);
            for (var loggrp : loggrp.values()) {
                loggrp.enabled = sect.Get_bool(loggrp.front.toLowerCase());
            }
        }
    };

    static final String[] names = {
            "ALL",
            "VGA",
            "VGAGFX",
            "VGAMISC",
            "INT10",
            "SB",
            "DMACONTROL",

            "FPU",
            "CPU",
            "PAGING",

            "FCB",
            "FILES",
            "IOCTL",
            "EXEC",
            "DOSMISC",

            "PIT",
            "KEYBOARD",
            "PIC",

            "MOUSE",
            "BIOS",
            "GUI",
            "MISC",

            "IO",
    };

    public static void LOG_StartUp() {
        for (String name : names) {
            loggrp.put("LOG_" + name, new Debug_gui._LogGroup());
            loggrp.get("LOG_" + name).front = name;
        }

        /* Register the log section */
//        Section_prop sect= Dosbox.control.AddSection_prop("log",LOG_Init);
//        Prop_string Pstring = sect.Add_string("logfile", Property.Changeable.Always,"");
//        Pstring.Set_help("file where the log messages will be saved to");
//        for (int i=1;i<LogTypes.LOG_MAX;i++) {
//            Prop_bool Pbool = sect.Add_bool(loggrp[i].front.toLowerCase(),Property.Changeable.Always,true);
//            Pbool.Set_help("Enable/Disable logging of this type.");
//        }
//        Msg.add("LOG_CONFIGFILE_HELP","Logging related options for the debugger.\n");
    }
}
