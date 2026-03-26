package jdos.misc.setup;

import java.io.IOException;
import java.io.OutputStream;


public class Section_line extends Section {

    public Section_line(String _sectionname) {
        super(_sectionname);
    }

    @Override
    public void handleInputline(String input) {
        data += input;
        data += "\n";
    }

    @Override
    public void printData(OutputStream os) throws IOException {
        Config.fputs(data, os);
    }

    @Override
    public String getPropValue(String _property) {
        return NO_SUCH_PROPERTY;
    }

    public String data = "";
}
