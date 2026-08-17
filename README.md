[![Release](https://jitpack.io/v/umjammer/vavi-apps-dosbox.svg)](https://jitpack.io/#umjammer/vavi-apps-dosbox)
[![Java CI](https://github.com/umjammer/vavi-apps-dosbox/actions/workflows/gradle.yml/badge.svg)](https://github.com/umjammer/vavi-apps-dosbox/actions/workflows/gradle.yml)
[![CodeQL](https://github.com/umjammer/vavi-apps-dosbox/actions/workflows/codeql.yml/badge.svg)](https://github.com/umjammer/vavi-apps-dosbox/actions/workflows/codeql.yml)
![Java](https://img.shields.io/badge/Java-25-b07219)

# vavi-apps-dosbox

<img alt="logo" src="other/duke_at.png" width="160" />

🕹️ Java x86 emulator based on [jDOSBox](https://github.com/Tennessene/jDOSBox), with a capability running .exe w/ win32 dll 

## Install

 * [gradle](https://jitpack.io/#umjammer/vavi-apps-dosbox)

## Usage

* `MMFTOOLC.EXE` uses `M5_EmuSmw5.dll` that uses win32 api

```shell
$ java -jar /Users/nsano/src/java/jDOSBox/launcher/build/libs/launcher-0.74.31.jar \
  -c 'mount c /usr/local' \
  -c 'c:' \
  -c 'cd MMFTOOL' \
  -c 'MMFTOOLC.EXE TEST.MMF'
```

### embedding

`jdos.api.JDosBox` runs a machine from inside another program. Three of its outputs can be taken
rather than left to the host: `waveOutSink` for what a win32 guest writes to its `waveOut` device,
`directSoundSink` for what it writes to a DirectSound buffer (both `AudioSink`, whose `write`
blocking is also what paces a `turbo` machine), and `stdioSink` for what the guest writes to its
own `stdout` and `stderr` (`StdioSink` - jdosbox's own diagnostics are not included).

Each chunk of guest output is stamped with the audio the guest had produced when it wrote it, in
frames of the `AudioSink` stream. That is what lets a host line up what a guest says with the
sound it is saying it about: a machine used as an audio source runs seconds ahead of what is
being heard, so the text is that far ahead of the sound without it.

```java
JDosBox dosbox = new JDosBox()
        .mount('c', dir)
        .command("c:")
        .command("player.exe song.dat")
        .waveOutSink(mySink)
        .stdioSink((data, offset, length, frames) -> ...)
        .turbo(true)
        .exitWhenProgramFinishes(true);
dosbox.start();
```

### a DirectSound buffer as a stream

A sound card is a place: it keeps playing whatever it was last given, and a program that writes
nothing to it hears the last thing again. An `AudioSink` is a stream, where everything handed
over is heard once and in order. `directSoundSink` therefore plays the buffer differently from
the way the host's sound card is played - it is given what the program has written since the last
time and nothing else, and nothing at all while the program has written nothing, which is also
what keeps a program that is still loading from being run flat out into the sink.

One chunk of what was written is always left untaken. A ring buffer in which the play cursor has
caught the write cursor is a ring with no free space in it, which is exactly what a full one
looks like; a program that reads it that way stops writing, and the song stops with it.

The first secondary buffer that plays claims the sink - the primary buffer carries the format
rather than the sound, so it never does.

### reading a guest's shared memory

`jdos.win.api.SharedMemory` reads a named file mapping the guest made, from the host. On a real
PC a program that wants to know what another one is doing opens the mapping the other one
published; here the reader is the program embedding the machine, and there is nothing to call
`OpenFileMapping` with, so this is that call made from outside.

```java
byte[] work = new byte[6720];
SharedMemory.read("FMP7_PUBLIC_WORK", 0, work, 0, work.length);
```

The guest is not stopped to read it, so what comes back can be half of one update and half of the
next - the guest's own mutex is a guest object and cannot be taken from here. For a display of
what is playing that is a wrong pixel for a frame; for anything that has to be consistent, read
twice and compare.

`Fmp7WorkProbe` is both the example and the test: it runs `FMP7.exe`, takes its sound through
`directSoundSink` and what it says it is playing through `SharedMemory`.

Note that a mapping's view needs somewhere to go, and until recently there was nowhere: the
address `MapViewOfFile` reserved came from outside the range the process hands addresses out of,
so it answered 0 and the view was mapped over the bottom of the process, where the next thing
mapped landed on top of it. Anything reading a mapping before that read whatever had been mapped
there last.

### settings for a win32 guest

A program keeps its settings in the registry, and a machine starts with an empty one - so a guest
runs on its built-in defaults and the dialog it offers for changing them is no use with nobody at
the keyboard. A `jdosbox.reg` beside the program is read into the registry before it starts
(`-Djdos.registry=<file>` names one elsewhere), in the format regedit writes:

```
[HKEY_CURRENT_USER\SOFTWARE\Guu\FMP7]
"ReSamplePower"=dword:00000000
```

### running a win32 program for its sound alone

`-Djdos.novideo=true` runs a win32 guest with nothing drawn: no window is repainted, no blit is
carried out, no pixels reach the screen. A player redrawing its level meters spends real time on
it - about half of everything the machine does, measured on `FMP7.exe` - and that time is taken
from whatever is making the sound. The two `FMP7.exe` tests turn it on themselves unless the
property is already set.

`-Djdos.compile.sse=false` puts the sse instructions back on the interpreter, which is where they
were before the compiler learned them - a way back if something compiled there looks wrong.

### keeping time

A program that plays music ticks on a multimedia timer - `FMP7.exe` asks for one every 10ms - and
the song is as long as those ticks make it. The scheduler used to round every sleep up to the next
ten milliseconds and add one for luck, so a 10ms timer fired every 16.7ms and the music played
two thirds slower than it should, with the sound breaking up because the machine could not fill
the buffer at that rate either. It now waits exactly as long as the next thread is due in, and a
periodic timer is scheduled on an absolute timeline so a slow callback cannot push the rest of the
song later. A line every five seconds says what the timer asked for and what it is getting.

### is the sound breaking up?

`TestCase#testChoppiness` plays for a while and then asks the sound path how it went, so that
"it sounds choppy" is a number:

```shell
$ ./gradlew :jdosbox:test --tests TestCase.testChoppiness -Dvavi.test=ai -Dtimeout=40
```

It counts *breaks* - the sound card was left with nothing to play - and *repeats* - what went to
the card was exactly what went before it, which is the machine failing to render in time and the
same sound coming out twice. Silence repeats itself and is not counted. While it plays, a line a
second says what a second of sound costs in processor time.

The repeat count is worth watching after any change to the cpu: it found a compiled `comisd` that
did not say which flags it writes, which made the compiler read a flag from the instruction
before it - the sound went wrong long before anything crashed.

For `FMP7.exe` this is what makes it keep up: with its own defaults it needs ~98% of real time to
synthesize, so anything at all breaks the sound up, and turning its oversampling off brings that
to ~83%. Its output is a 48kHz stereo DirectSound buffer whatever the settings say.

## References

 * [original](https://github.com/Tennessene/jDOSBox)
 * https://github.com/umjammer/mmftool (sample above)
 * https://github.com/farmboy0/JPC

### Tech Know

 * ~~graalvm doesn't make choppy sound~~ ... because of heavy debug logging
   * graalvm is faster than openjdk is real

## TODO

 * a program that runs after a machine that was stopped part way through a song can fail to
   start - `FMP7.exe` throws an error code of its own and dies before it makes a sound, having
   loaded and set up its DirectSound buffers. The win32 layer is a whole system in statics for a
   process that runs one program and exits, and this is one more thing in it that a machine
   leaves behind; the interface tables and the emulated devices, which were two others, are now
   thrown away in `WinSystem#stop`.

 * ~~pc98~~ ... use j98
 * keep aspect ratio
 * debug
   * KAFFE.EXE crashes
 * junit -> jupiter

---

[Original](https://github.com/Tennessene/jDOSBox)
==========
Java x86 emulator based on DOSBox
--------------------------------------------------

This was continued from revision 808 (the last revision on SourceForge). The original source is available at SourceForge's [code page](https://sourceforge.net/p/jdosbox/code/HEAD/tree). It was cloned using git-svn and uploaded to this repo. Credit goes to James Bryant for creating jDOSBox. Thanks for inspiring me with this product James!

Further Information
-----------
The original Readme is the ReadMe.txt. jDOSBox can be used with Java 8 or higher, just make sure `threshold` under the compiler section of the config is set to `0` if you're going to use Java 8 as the compiler only work in later Java versions for now. The network support is also experimental, so don't expect it to always work and we're working on a build of jdosbox_pcap that doesn't require native libraries. According to the original author, James Bryant, it is able to run Windows apps with no OS and even run Windows XP. We have yet to figure out how to do that! Windows 95 works well though.

We found the original website with more information here: [Status | jDosbox](https://web.archive.org/web/20190304011952/http://jdosbox.sourceforge.net/cms/)

Builds
------
 * jdosbox.jar 

Standard build.  Good for most people.  Includes the recompiler and references no native code.

 * jdosbox_applet.jar

Lite build.  Includes everything from jdosbox.jar except for the recompiler.  The recompiler
would not work in unsigned applets anyway, so no reason to include it.  For signed applets
go ahead and use the standard build since it will be faster

 * jdosbox_pcap.jar

Ethernet build.  This includes experimental support for the NE2000 ethernet card via native pcap
library.

Command line options
------
-compile <name>

Note: Not available in jdosbox_applet.jar
  
 * Example: `java -jar jdosbox.jar -compile doom`
  
Result: This will produce a file called doom.jar in the current directory with blocks that were
compiled while you ran the program.

See the [compiler] section in the config file for options to fine-tune what gets compiled.

-fullscreen

 * Starts the app in full-screen mode

-pcap
-pcapport

 * See NE2000 Ethernet section

Applets
------
### Use an image from within a JAR.  

 * Pro: This works well for signed and unsigned applets.
 * Cons: A little slower and read-only.  No save games.

To create an image I would recommend using DOSBox Megabuild at http://home.arcor.de/h-a-l-9000/

```
<APPLET CODE="jdos.gui.MainApplet" archive='jdosbox.jar,doom.jar' WIDTH=640 HEIGHT=400>
<param name="param1" value="imgmount e jar://doom.img -size 512,16,2,512"">
<param name="param2" value="e:">
<param name="param3" value="doom">
</APPLET>
```

More Info: Make sure you put the image file inside a subdirectory called jdos.  This file may
have the extension either jar or zip.

### Download, and unzip files to the client machine

 * Pro: This allows save games to work
 * Con: The applets must be signed (can be self-signed)

```
<APPLET CODE="jdos.gui.MainApplet" archive='jdosbox.jar' WIDTH=640 HEIGHT=400>
<param name="download1" value="http://jdosbox.sourceforge.net/doom.04.zip">
<param name="param1" value="imgmount e ~/.jdosbox/doom.04/doom.img -size 512,16,2,512">
<param name="param2" value="e:">
<param name="param3" value="doom">
<param name="param4" value="-conf ~/.jdosbox/doom.04/dosbox.conf">
</APPLET>
```

More Info: The `~` folder is the home directory for the user.  On Windows, this might be `c:\users\name\`
jDOSBox will download the zip mentioned in the download1 parameter and unzip it in the folder
`~/.jdosbox/<zip name>/` then it is up to you to issue the proper mount command.  You can either
mount an image file you just downloaded via imgmount.  Or you can mount the entire directory.

You have to specify the width and height of the applet object ahead of time, but jDOSBox will set the
size of the screen it will use at run time.  If the jDOSBox screen is smaller than the applet window
then the screen will be centered.  The background color around the screen defaults to dark grey, but you
may change that by setting the param "background-color".

 * Example: `<param name="background-color" value="#FFFFFF">`


NE2000 Ethernet
------
This requires native code which is why it is not part of the standard build

See the ReadMe.txt in the pcap directory (only applies to certain builds).

Compiling from source
------
The compilation from source is handled by Gradle using Java 8 or newer:
```
gradle build
```
The `launcher` submodule contains `fat` jar with all dependencies included.

---

<sub>image designed by @umjammer, drawn by nano banana</sub>
