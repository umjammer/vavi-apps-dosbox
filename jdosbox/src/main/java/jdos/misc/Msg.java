package jdos.misc;

import jdos.Dosbox;
import jdos.misc.setup.Prop_path;
import jdos.misc.setup.Section_prop;

import java.io.*;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.List;


/**
 * @deprecated use ResourceBundle
 */
@Deprecated
public class Msg {

    private static final Logger logger = System.getLogger(Msg.class.getName());

    static class MessageBlock {
        final String name;
        final String val;
        public MessageBlock(String _name, String _val) {
            name = _name;
            val = _val;
        }
    }

    static final List<MessageBlock> Lang = new ArrayList<>();

    static public void add(String name, String value) {
        for (MessageBlock m : Lang) {
            if (m.name.equals(name))
                return;
        }
        Lang.add(new MessageBlock(name, value));
    }

    static public void replace(String name, String value) {
        for (int i=0;i<Lang.size();i++) {
            MessageBlock m = Lang.get(i);
            if (m.name.equals(name))
                Lang.remove(m);
        }
        Lang.add(new MessageBlock(name, value));
    }

    static public void LoadMessageFile(String fname) {
        if (fname == null || fname.isEmpty()) return; //empty string=no languagefile
        FileReader fr=null;
        try {
            fr = new FileReader(fname);
        } catch (FileNotFoundException e) {
            throw new IllegalStateException("MSG:Can't load messages: "+fname);
        }
        BufferedReader br = new BufferedReader(fr);
        String linein;
        String name="";
        String string="";
        try {
            while ((linein=br.readLine()) != null) {
                /* New string name */
                if (linein.startsWith(":")) {
                    string="";
                    name=linein.substring(1);
                /* End of string marker */
                } else if (linein.startsWith(".")) {
                    /* Replace/Add the string to the internal languagefile */
			        /* Remove last newline (marker is \n.\n) */
                    if (string.endsWith("\n"))
                        string = string.substring(0, string.length()-1); //Second if should not be needed, but better be safe.
                    replace(name, string);
                } else {
                    string+=linein+"\n";
                }
            }
        } catch (IOException e) {

        }
        if (fr != null) {
            try {fr.close();} catch (Exception e){}
        }
    }

    static public String get(String msg) {
        for (MessageBlock m : Lang) {
            if (m.name.equals(msg))
                return m.val;
        }
        return "Message not Found!\n";
    }

    static public void write(String location) {
        try (FileOutputStream fos = new FileOutputStream(location)) {
            for (MessageBlock m : Lang) {
                String line = ":" + m.name + "\n" + m.val + "\n.\n";
                fos.write(line.getBytes());
            }
        } catch (Exception e) {
            logger.log(Level.ERROR, e.getMessage(), e);
        }
    }

    static public void init(Section_prop section) {
        String file_name = Dosbox.control.cmdline.findString("-lang", true);
        if (file_name != null) {
            LoadMessageFile(file_name);
        } else {
            Prop_path pathprop = section.Get_path("language");
            if (pathprop != null) LoadMessageFile(pathprop.realpath);
        }
    }
}
