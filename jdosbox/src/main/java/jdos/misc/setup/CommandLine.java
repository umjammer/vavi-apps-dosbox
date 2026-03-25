package jdos.misc.setup;

import jdos.util.StringRef;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


public class CommandLine {
    public CommandLine(String[] args) {
        cmds = new ArrayList<>(args.length);
        cmds.addAll(Arrays.asList(args));
        // TODO file_name = ?
    }

    public CommandLine(String name,String cmdline) {
        cmds = new ArrayList<>();
        fileName =name;
        /* Parse the cmds and put them in the list */
        boolean inword,inquote;char c;
        inword=false;inquote=false;
        StringBuilder str = new StringBuilder();
        for (int i=0;i<cmdline.length();i++) {
            c = cmdline.charAt(i);
            if (inquote) {
                if (c!='"')
                    str.append(c);
                else {
                    inquote=false;
                    cmds.add(str.toString());
                    str = new StringBuilder();
                }
            } else if (inword) {
                if (c!=' ')
                    str.append(c);
                else {
                    inword=false;
                    cmds.add(str.toString());
                    str = new StringBuilder();
                }
            }
            else if (c=='"') { inquote=true;}
            else if (c!=' ') { str.append(c);inword=true;}
        }
        if (inword || inquote) cmds.add(str.toString());
    }

    public String getFileName() {
        return fileName;
    }
    public boolean findExist(String name) {
        return findExist(name, false);
    }
    public boolean findExist(String name, boolean remove) {
        int index = FindEntry(name, false);
        if (index < 0) return false;
        if (remove) cmds.remove(index);
        return true;
    }

    public Integer findHex(String name) {
        return findHex(name, false);
    }
    public Integer findHex(String name, boolean remove) {
        int index = FindEntry(name, true);
        if (index < 0) return null;
        try {
            Integer result = Integer.parseInt(cmds.get(index+1), 16);
            if (remove) {
                cmds.remove(index);
                cmds.remove(index);
            }
            return result;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public Integer findInt(String name) {
        return findInt(name, false);
    }
    public Integer findInt(String name, boolean remove) {
        int index = FindEntry(name, true);
        if (index < 0) return null;
        try {
            Integer result = Integer.parseInt(cmds.get(index+1), 10);
            if (remove) {
                cmds.remove(index);
                cmds.remove(index);
            }
            return result;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public String findString(String name) {
        return findString(name, false);
    }
    public String findString(String name, boolean remove) {
        int index = FindEntry(name, true);
        if (index < 0) return null;
        String result = cmds.get(index+1);
        if (remove) {
            cmds.remove(index);
            cmds.remove(index);
        }
        return result;
    }

    public String findCommand(int which) {
        if (which<1) return null;
        if (which>cmds.size()) return null;
        return cmds.get(which-1);
    }

    public String findStringBegin(String begin) {
        return findStringBegin(begin, false);
    }
    public String findStringBegin(String begin, boolean remove) {
        begin = begin.toLowerCase();
        for (int i=0;i<cmds.size();i++) {
            if (cmds.get(i).toLowerCase().startsWith(begin)) {
                String result = cmds.get(i);
                if (remove)
                    cmds.remove(i);
                return result;
            }
        }
        return null;
    }

    public String findStringRemain(String name) {
        int index = FindEntry(name, false);
        if (index < 0) return null;
        index++;
        StringBuilder value = new StringBuilder();
        for (int i=index;i<cmds.size();i++) {
            value.append(" ");
            value.append(cmds.get(i));
        }
        return value.toString();
    }

    /**
     * Only used for parsing command.com /C
     * Allowing /C dir and /Cdir
     * Restoring quotes back into the commands so command /C mount d "/tmp/a b" works as intended
     */
    public boolean findStringRemainBegin(String name, StringRef value) {
        int i=-1;
        value.value = "";
        if ((i=FindEntry(name, false)) < 0) {
            int len = name.length();
            boolean found = false;
            for (i = 0; i < cmds.size(); i++) {
                String s = cmds.get(i);
                if (s.length() > len) s = s.substring(0, len);
                if (s.equalsIgnoreCase(name)) {
                    String temp = "";
                    s = cmds.get(i);
                    if (s.length() > len)
                        temp = s.substring(len);
                    //Restore quotes for correct parsing in later stages
                    if (temp.contains(" "))
                        value.value = "\"" + temp + "\"";
                    else
                        value.value = temp;
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        i++;
        for (; i < cmds.size(); i++) {
            value.value += " ";
            String temp = cmds.get(i);
            if (temp.contains(" "))
                value.value += "\"" + temp + "\"";
            else
                value.value += temp;
        }
        return true;
    }

    public String getStringRemain() {
        if (cmds.isEmpty()) return null;
        StringBuilder value = new StringBuilder();
        for (int i=0;i<cmds.size();i++) {
            if (i>0)
                value.append(" ");
            value.append(cmds.get(i));
        }
        return value.toString();
    }

    public void shift() {
        shift(1);
    }
    public void shift(int amount) {
        for (int i=0;i<amount;i++) {
            fileName = !cmds.isEmpty() ? cmds.getFirst() :"";
            if (!cmds.isEmpty()) cmds.removeFirst();
        }
    }
    public int getCount() {
        return cmds.size();
    }
    public int getArgLength() {
        int result = 0;
        for (int i=0;i<cmds.size();i++) {
            if (i>0)
                result++;
            result+= cmds.get(i).length();
        }
        return result;
    }

    private final List<String> cmds;
    private String fileName;
    private int FindEntry(String name, boolean needNext) {
        for (int i=0;i<cmds.size();i++) {
            if (cmds.get(i).equalsIgnoreCase(name)) {
                if (needNext && i==cmds.size()-1)
                    return -1;
                return i;
            }
        }
        return -1;
    }
}
