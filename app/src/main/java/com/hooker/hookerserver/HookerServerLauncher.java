package com.hooker.hookerserver;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dalvik.system.DexClassLoader;
import dalvik.system.DexFile;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class HookerServerLauncher implements IXposedHookLoadPackage {

    private static boolean started = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) throws Throwable {
        //生成代码查找在app目录下是否存在一个叫hooker_server.conf的文件，如果存在读取并往下执行，如果不存在则直接返回
        XposedBridge.log("hooker_server handleLoadPackage");
        if (!loadPackageParam.packageName.equals(loadPackageParam.processName)) {
            return; // 只在主进程执行
        }
        XposedHelpers.findAndHookMethod(
                Application.class,
                "attach",
                Context.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Context context = (Context) param.args[0];
                        String packageName = context.getPackageName();
                        //扫描/data/local/tmp/hooker_server_conf是否存在配置文件，如果不存在则返回
                        File confDir = new File("/data/local/tmp/hooker_server_conf");
                        if (!confDir.exists() || !confDir.isDirectory()) {
                            XposedBridge.log("Not found hooker_server_conf dir");
                            return;
                        }
                        File confFile = new File(confDir, packageName + ".conf");
                        if (!confFile.exists()) {
                            XposedBridge.log("Not found " + packageName + ".conf");
                            return;
                        }
                        XposedBridge.log("Found config: " + confFile.getAbsolutePath());
                        // 读取配置文件
                        HookerServerConf config = loadConfig(confFile);
                        Log.i("hooker_server", config.toString());
                        if (started) return;
                        started = true;
                        delayLoadPatch(context, config.controller_dex, config.controller_class);
                    }
                }
        );
    }

    private void delayLoadPatch(Context context, String dexPath, List<String> clazzList) {

        new java.util.Timer().schedule(
                new java.util.TimerTask() {
                    @Override
                    public void run() {
                        XposedBridge.log("HookerServer: start loadPatch after 10s");
                        loadPatch(context, dexPath, clazzList);
                    }
                },
                10000 // 10秒
        );
    }

    public class HookerServerConf {
        public String controller_dex;

        public List<String> controller_class = new ArrayList<>();

        @Override
        public String toString() {
            return "HookerServerConf{" +
                    ", controller_dex='" + controller_dex + '\'' +
                    ", controller_class=" + controller_class +
                    '}';
        }
    }

    private HookerServerConf loadConfig(File conf) throws Exception {
        HookerServerConf config = new HookerServerConf();
        BufferedReader reader = new BufferedReader(new FileReader(conf));
        Pattern pattern = Pattern.compile("(\\w+)\\s*=\\s*(.+);");
        String line;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.startsWith("#") || line.isEmpty())
                continue;
            Matcher m = pattern.matcher(line);
            if (!m.find())
                continue;
            String key = m.group(1).trim();
            String value = m.group(2).trim();
            switch (key) {
                case "controller_dex":
                    config.controller_dex = value;
                    break;
                case "controller_class":
                    String[] arr = value.split(",");
                    List<String> list = new ArrayList<>();
                    for (String s : arr) {
                        list.add(s.trim());
                    }
                    config.controller_class = list;
                    break;
            }
        }
        reader.close();
        return config;
    }

    private void loadPatch(Context context, String dexPath, List<String> clazzList) {
        try {
            File cacheDir;
            if (Build.VERSION.SDK_INT >= 21) {
                cacheDir = context.getCodeCacheDir();
            } else {
                cacheDir = context.getCacheDir();
            }
            ClassLoader parent = context.getClassLoader();
            String fullDexPath = dexPath == null ?
                    "/data/local/tmp/radar.dex" :
                    "/data/local/tmp/radar.dex:" + dexPath;
            DexClassLoader loader = new DexClassLoader(
                    fullDexPath,
                    cacheDir.getAbsolutePath(),
                    null,
                    parent
            );
            Class<?> bootClass = loader.loadClass("gz.httpserver.HookerWebServerBoot");
            Method method;
            if (clazzList != null && !clazzList.isEmpty()) {
                method = bootClass.getDeclaredMethod(
                        "scanAndStartHttpServer",
                        List.class
                );
                method.invoke(null, clazzList);
            } else {
                method = bootClass.getDeclaredMethod(
                        "startDefaultHttpServer"
                );
                method.invoke(null);
            }
        } catch (Throwable e) {
            XposedBridge.log(e);
        }
    }
}
