package jdos.cpu;

import java.util.ArrayList;
import java.util.List;

import jdos.cpu.Core_normal.State;


public class Core_full {

    static final List<State> state = new ArrayList<>();

    public static void pushState() {
        Core_normal.State s = new Core_normal.State();
        Core_normal.saveState(s);
        state.add(s);
    }

    public static void removeState() {
        state.removeLast();
    }

    public static void popState() {
        Core_normal.State s = state.removeLast();
        Core_normal.loadState(s);
    }

    /*Bits*/
    public static final CPU.CPU_Decoder CPU_Core_Full_Run = Core_normal.CPU_Core_Normal_Run::call;

    public static void CPU_Core_Full_Init() {

    }
}
