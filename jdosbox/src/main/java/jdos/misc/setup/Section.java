package jdos.misc.setup;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;


public abstract class Section {

    public static final String NO_SUCH_PROPERTY = "PROP_NOT_EXIST";

    public interface SectionFunction {

        void call(Section section);
    }

    private static class Function_wrapper {

        final SectionFunction function;
        final boolean canchange;

        Function_wrapper(SectionFunction _fun, boolean _ch) {
            function = _fun;
            canchange = _ch;
        }
    }

    private final List<Function_wrapper> initfunctions = new ArrayList<>();
    private final List<Function_wrapper> destroyfunction = new ArrayList<>();
    private final String sectionname;

    public Section(String _sectionname) {
        sectionname = _sectionname;
    }

    public void addInitFunction(SectionFunction func) {
        addInitFunction(func, false);
    }

    public void addInitFunction(SectionFunction func, boolean canchange) {
        initfunctions.add(new Function_wrapper(func, canchange));

    }

    public void addDestroyFunction(SectionFunction func) {
        addDestroyFunction(func, false);
    }

    public void addDestroyFunction(SectionFunction fun, boolean canchange) {
        destroyfunction.add(new Function_wrapper(fun, canchange));
    }

    public void executeInit() {
        executeInit(true);
    }

    public void executeInit(boolean initall) {
        for (Function_wrapper f : initfunctions) {
            if (initall || f.canchange) f.function.call(this);
        }
    }

    public void executeDestroy() {
        executeDestroy(true);
    }

    public void executeDestroy(boolean destroyall) {
        for (Function_wrapper f : destroyfunction) {
            if (destroyall || f.canchange) f.function.call(this);
        }
    }

    public String getName() {
        return sectionname;
    }

    public abstract String getPropValue(String _property);

    public abstract void handleInputline(String _line);

    public abstract void printData(OutputStream os) throws IOException;
}
