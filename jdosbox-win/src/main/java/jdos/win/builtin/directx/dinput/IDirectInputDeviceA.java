package jdos.win.builtin.directx.dinput;

import jdos.cpu.CPU;
import jdos.cpu.Callback;
import jdos.win.builtin.HandlerBase;
import jdos.win.builtin.directx.ddraw.IUnknown;

public class IDirectInputDeviceA extends IUnknown {
    static final int VTABLE_SIZE = 15;

    static final int OFFSET_FLAGS = 0;
    static final int DATA_SIZE = 4;


    private static int createVTable() {
        int address = allocateVTable("IDirectInputDeviceA", VTABLE_SIZE);
        addIDirectSound(address);
        return address;
    }

    static int addIDirectSound(int address) {
        address = addIUnknown(address);
        address = add(address, GetCapabilities);
        address = add(address, EnumObjects);
        address = add(address, GetProperty);
        address = add(address, SetProperty);
        address = add(address, Acquire);
        address = add(address, Unacquire);
        address = add(address, GetDeviceState);
        address = add(address, GetDeviceData);
        address = add(address, SetDataFormat);
        address = add(address, SetEventNotification);
        address = add(address, SetCooperativeLevel);
        address = add(address, GetObjectInfo);
        address = add(address, GetDeviceInfo);
        address = add(address, RunControlPanel);
        address = add(address, Initialize);
        return address;
    }

    public static int create() {
        return create("IDirectInputDeviceA", 0);
    }

    public static int create(String name, int flags) {
        int vtable = getVTable(name);
        if (vtable == 0)
            vtable = createVTable();
        int address = allocate(vtable, DATA_SIZE, 0);
        setData(address, OFFSET_FLAGS, flags);
        return address;
    }

    // HRESULT GetCapabilities(this, LPDIDEVCAPS lpDIDevCaps)
    static private final Callback.Handler GetCapabilities = new HandlerBase() {
        @Override
        public java.lang.String getName() {
            return "IDirectInputDeviceA.GetCapabilities";
        }
        @Override
        public void onCall() {
            int This = CPU.CPU_Pop32();
            int lpDIDevCaps = CPU.CPU_Pop32();
            notImplemented();
        }
    };

    // HRESULT EnumObjects(this, LPDIENUMDEVICEOBJECTSCALLBACKA lpCallback, LPVOID pvRef, DWORD dwFlags)
    static private final Callback.Handler EnumObjects = new HandlerBase() {
        @Override
        public java.lang.String getName() {
            return "IDirectInputDeviceA.EnumObjects";
        }
        @Override
        public void onCall() {
            int This = CPU.CPU_Pop32();
            int lpCallback = CPU.CPU_Pop32();
            int pvRef = CPU.CPU_Pop32();
            int dwFlags = CPU.CPU_Pop32();
            notImplemented();
        }
    };

    // HRESULT GetProperty(this, REFGUID rguidProp, LPDIPROPHEADER pdiph)
    static private final Callback.Handler GetProperty = new HandlerBase() {
        @Override
        public java.lang.String getName() {
            return "IDirectInputDeviceA.GetProperty";
        }
        @Override
        public void onCall() {
            int This = CPU.CPU_Pop32();
            int rguidProp = CPU.CPU_Pop32();
            int pdiph = CPU.CPU_Pop32();
            notImplemented();
        }
    };

    // HRESULT SetProperty(this, REFGUID rguidProp, LPCDIPROPHEADER pdiph)
    static private final Callback.Handler SetProperty = new HandlerBase() {
        @Override
        public java.lang.String getName() {
            return "IDirectInputDeviceA.SetProperty";
        }
        @Override
        public void onCall() {
            int This = CPU.CPU_Pop32();
            int rguidProp = CPU.CPU_Pop32();
            int pdiph = CPU.CPU_Pop32();
            notImplemented();
        }
    };

    // HRESULT Acquire(this)
    static private final Callback.Handler Acquire = new HandlerBase() {
        @Override
        public java.lang.String getName() {
            return "IDirectInputDeviceA.Acquire";
        }
        @Override
        public void onCall() {
            int This = CPU.CPU_Pop32();
            notImplemented();
        }
    };

    // HRESULT Unacquire(this)
    static private final Callback.Handler Unacquire = new HandlerBase() {
        @Override
        public java.lang.String getName() {
            return "IDirectInputDeviceA.Unacquire";
        }
        @Override
        public void onCall() {
            int This = CPU.CPU_Pop32();
            notImplemented();
        }
    };

    // HRESULT GetDeviceState(this, DWORD cbData, LPVOID lpvData)
    static private final Callback.Handler GetDeviceState = new HandlerBase() {
        @Override
        public java.lang.String getName() {
            return "IDirectInputDeviceA.GetDeviceState";
        }
        @Override
        public void onCall() {
            int This = CPU.CPU_Pop32();
            int cbData = CPU.CPU_Pop32();
            int lpvData = CPU.CPU_Pop32();
            notImplemented();
        }
    };

    // HRESULT GetDeviceData(this, DWORD cbObjectData, LPDIDEVICEOBJECTDATA rgdod, LPDWORD pdwInOut, DWORD dwFlags)
    static private final Callback.Handler GetDeviceData = new HandlerBase() {
        @Override
        public java.lang.String getName() {
            return "IDirectInputDeviceA.GetDeviceData";
        }
        @Override
        public void onCall() {
            int This = CPU.CPU_Pop32();
            int cbObjectData = CPU.CPU_Pop32();
            int rgdod = CPU.CPU_Pop32();
            int pdwInOut = CPU.CPU_Pop32();
            int dwFlags = CPU.CPU_Pop32();
            notImplemented();
        }
    };

    // HRESULT SetDataFormat(this, LPCDIDATAFORMAT lpdf)
    static private final Callback.Handler SetDataFormat = new HandlerBase() {
        @Override
        public java.lang.String getName() {
            return "IDirectInputDeviceA.SetDataFormat";
        }
        @Override
        public void onCall() {
            int This = CPU.CPU_Pop32();
            int lpdf = CPU.CPU_Pop32();
            notImplemented();
        }
    };

    // HRESULT SetEventNotification(this, HANDLE hEvent)
    static private final Callback.Handler SetEventNotification = new HandlerBase() {
        @Override
        public java.lang.String getName() {
            return "IDirectInputDeviceA.SetEventNotification";
        }
        @Override
        public void onCall() {
            int This = CPU.CPU_Pop32();
            int hEvent = CPU.CPU_Pop32();
            notImplemented();
        }
    };

    // HRESULT SetCooperativeLevel(this, HWND hwnd, DWORD dwFlags)
    static private final Callback.Handler SetCooperativeLevel = new HandlerBase() {
        @Override
        public java.lang.String getName() {
            return "IDirectInputDeviceA.SetCooperativeLevel";
        }
        @Override
        public void onCall() {
            int This = CPU.CPU_Pop32();
            int hwnd = CPU.CPU_Pop32();
            int dwFlags = CPU.CPU_Pop32();
            notImplemented();
        }
    };

    // HRESULT GetObjectInfo(this, LPDIDEVICEOBJECTINSTANCEA pdidoi, DWORD dwObj, DWORD dwHow)
    static private final Callback.Handler GetObjectInfo = new HandlerBase() {
        @Override
        public java.lang.String getName() {
            return "IDirectInputDeviceA.GetObjectInfo";
        }
        @Override
        public void onCall() {
            int This = CPU.CPU_Pop32();
            int pdidoi = CPU.CPU_Pop32();
            int dwObj = CPU.CPU_Pop32();
            int dwHow = CPU.CPU_Pop32();
            notImplemented();
        }
    };

    // HRESULT GetDeviceInfo(this, LPDIDEVICEINSTANCEA pdidi)
    static private final Callback.Handler GetDeviceInfo = new HandlerBase() {
        @Override
        public java.lang.String getName() {
            return "IDirectInputDeviceA.GetDeviceInfo";
        }
        @Override
        public void onCall() {
            int This = CPU.CPU_Pop32();
            int pdidi = CPU.CPU_Pop32();
            notImplemented();
        }
    };

    // HRESULT RunControlPanel(this, HWND hwndOwner, DWORD dwFlags)
    static private final Callback.Handler RunControlPanel = new HandlerBase() {
        @Override
        public java.lang.String getName() {
            return "IDirectInputDeviceA.RunControlPanel";
        }
        @Override
        public void onCall() {
            int This = CPU.CPU_Pop32();
            int hwndOwner = CPU.CPU_Pop32();
            int dwFlags = CPU.CPU_Pop32();
            notImplemented();
        }
    };

    // HRESULT Initialize(this, HINSTANCE hinst, DWORD dwVersion, REFGUID rguid)
    static private final Callback.Handler Initialize = new HandlerBase() {
        @Override
        public java.lang.String getName() {
            return "IDirectInputDeviceA.Initialize";
        }
        @Override
        public void onCall() {
            int This = CPU.CPU_Pop32();
            int hinst = CPU.CPU_Pop32();
            int dwVersion = CPU.CPU_Pop32();
            int rguid = CPU.CPU_Pop32();
            notImplemented();
        }
    };
}
