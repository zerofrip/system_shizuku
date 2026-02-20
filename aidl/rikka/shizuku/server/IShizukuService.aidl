package rikka.shizuku.server;

/**
 * Standard Shizuku interface.
 * Implemented as a system service.
 */
interface IShizukuService {

    /**
     * Protocol version.
     */
    int getVersion();

    int getUid();

    int checkSelfPermission(String permission);

    void requestPermission(int requestCode);

    String getToken();

    void newProcess(in String[] cmd, in String[] env, in String dir);
}
