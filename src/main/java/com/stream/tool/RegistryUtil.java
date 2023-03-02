package com.stream.tool;

import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;

public class RegistryUtil {

    /**
     * 向指定目录下的keyPath中, 写入key-value
     * 当 HKEY不同时, 可能会出现 "拒绝访问" 的问题, 此时需要手动打开注册表, 修改对应的keyPath父级目录(此处为SOFTWARE)的全部读写权限
     *
     * @param value
     */
    public static boolean writeToRegistry(String key, String value) {
        // 判断KeyPath是否存在
        boolean isOkay = Advapi32Util.registryKeyExists(WinReg.HKEY_CURRENT_USER, "Software\\Valve\\Steam");
        // 若不存在
        if (!isOkay) {
            try {
                // 创建keyPath(即, 目录)
                Advapi32Util.registryCreateKey(WinReg.HKEY_CURRENT_USER, "Software\\Valve\\Steam");
            } catch (Exception e) {
                // 遇到异常, 写入失败
                return false;
            }
        }
        try {
            // 向指定目录下的KeyPath中, 写入key及其value
            Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, "Software\\Valve\\Steam", key, value);
        } catch (Exception e) {
            // 遇到异常, 写入失败
            return false;
        }
        // 写入成功
        return true;
    }

    // 删除注册表中的keyPath及旗下key-value对
    public static boolean deleteRegistryKey(String keyPath) {
        try {
            // Delete a key
            Advapi32Util.registryDeleteKey(WinReg.HKEY_CURRENT_USER, "SOFTWARE\\keyPath");
        } catch (Exception e) {
            // 删除失败
            return false;
        }
        // 删除成功
        return true;

    }

    // 从注册表中读取key对应的value值
    public static String readKeyFromRegistry(String key) {
        String value = "1";
        try {
            // 根据 key(value所在目录) 返回注册表中类型为 REG_SZ 的 value 对应的值
            value = Advapi32Util.registryGetStringValue(
                    WinReg.HKEY_CURRENT_USER, "Software\\Valve\\Steam", key);
//            String s = Advapi32Util.registryGetStringValue(
//                    WinReg.HKEY_CURRENT_USER, "Software\\Valve\\Steam\\");
            //System.out.println(s);
        } catch (Exception e) { // 对应key不存在
            value = null;
        }
        return value;

    }


    public static void main(String[] args) {

        boolean isOk;
        isOk = writeToRegistry("sun", "123");
        System.out.println("写入" + isOk);
        String value = readKeyFromRegistry("SteamPath");
        System.out.println(value);
        //isOk = deleteRegistryKey(Constants.KEY_PATH);
        //System.out.println("删除" + isOk);

    }

}