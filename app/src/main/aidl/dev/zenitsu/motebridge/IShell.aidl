package dev.zenitsu.motebridge;

// Runs as the shell user inside Shizuku: the only identity (besides root) that may read the Wii
// Remote's raw input devices and create a virtual controller.
interface IShell {
    void destroy() = 16777114;
    String run(String command) = 1;
    boolean startBridge(String executable) = 2;
    void stopBridge() = 3;
    boolean bridgeRunning() = 4;
    String bridgeLog() = 5;
}
