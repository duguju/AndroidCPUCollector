/*
 * Copyright (C) 2019 jjoeyang. All Rights Reserved.
 */
package com.yzz.cpucollector;

import android.util.Log;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * CPU占用统计类
 * 单例，进入APP时通过setPkgName()设置应用包名，调用getCPURate()即可输出CPU统计信息，退出时调用release()
 *
 * Created by jjoeyang on 19/5/6
 */
public class CPUCollector {
    public static final String TAG = CPUCollector.class.getSimpleName();
    private String mPkgName = null;
    private String mLastCPU = "";
    private Thread mCpuThread;
    private boolean enableCPU = true;
    private boolean isRunning = false;
    private boolean doCollect = false;
    private static final String COMMAND_SH = "sh";
    private static final String COMMAND_LINE_END = "\n";
    private static final String COMMAND_EXIT = "exit\n";

    private int maxFrameCount = 5; // 统计的总次数，可修改
    private int resultFrameTimes = 0; // 统计次数
    private double resultAVGValue;
    private String mAvgCPUValue;

    private static CPUCollector mInstance = null;

    private CPUCollector() {
        if (mCpuThread == null) {
            mCpuThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (enableCPU) {
                        if (doCollect && !isRunning) {
                            doCollect = false;
                            isRunning = true;
                            mLastCPU = getCPUFromTopCMD();
                            isRunning = false;
                        } else {
                            try {
                                Thread.sleep(10);
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                }
            });
            mCpuThread.setName("CpuCollectorThread");
            mCpuThread.start();
        }
    }

    public static synchronized CPUCollector getInstance() {
        if (mInstance == null) {
            mInstance = new CPUCollector();
        }
        return mInstance;
    }

    public void setPkgName(String pkgName) {
        mPkgName = pkgName;
    }

    public String getCPURate() {
        if (!isRunning && mPkgName != null) {
            doCollect = true;
        }
        return mLastCPU;
    }

    public String getAvgCPU() {
        return mAvgCPUValue;
    }

    public void release() {
        mPkgName = null;
        mAvgCPUValue = null;
        enableCPU = false;
        mCpuThread = null;
    }

    private String getCPUFromTopCMD() {
        String cpu = "";
        List<String> result = execute("top -n 1 -s cpu | grep " + mPkgName);
        if (result != null && result.size() > 0) {
            String r = result.get(0);
            if (r != null && r.contains("%")) {
                int end = r.indexOf("%");
                int start = -1;
                for (int i = end; i >= 0; i--) {
                    if (Character.isWhitespace(r.charAt(i))) {
                        start = i;
                        break;
                    }
                }
                if (start >= 0) {
                    cpu = r.substring(start, end);
                    calculateAVGValue(Double.parseDouble(cpu));
                }
            }
        }
        return cpu;
    }

    /**
     * 执行单条命令
     *
     * @param command
     * @return
     */
    private List<String> execute(String command) {
        return execute(new String[]{command});
    }

    /**
     * 可执行多行命令（bat）
     *
     * @param commands
     * @return
     */
    private List<String> execute(String[] commands) {
        List<String> results = new ArrayList<String>();
        int status = -1;
        if (commands == null || commands.length == 0) {
            return null;
        }
        Process process = null;
        BufferedReader successReader = null;
        BufferedReader errorReader = null;
        StringBuilder errorMsg = null;
        DataOutputStream dos = null;
        try {
            process = Runtime.getRuntime().exec(COMMAND_SH);
            dos = new DataOutputStream(process.getOutputStream());
            for (String command : commands) {
                if (command == null) {
                    continue;
                }
                dos.write(command.getBytes());
                dos.writeBytes(COMMAND_LINE_END);
                dos.flush();
            }
            dos.writeBytes(COMMAND_EXIT);
            dos.flush();

            status = process.waitFor();

            errorMsg = new StringBuilder();
            successReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            String lineStr;
            while ((lineStr = successReader.readLine()) != null) {
                results.add(lineStr);
            }
            while ((lineStr = errorReader.readLine()) != null) {
                errorMsg.append(lineStr);
            }
        } catch (IOException e) {
            e.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                if (dos != null) {
                    dos.close();
                }
                if (successReader != null) {
                    successReader.close();
                }
                if (errorReader != null) {
                    errorReader.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }

            if (process != null) {
                process.destroy();
            }
        }
        Log.d(TAG, (String.format(Locale.CHINA, "execute command end, errorMsg:%s, and status %d: ",
                errorMsg, status)));
        return results;
    }

    private void calculateAVGValue(double resultTime) {
        if (resultFrameTimes >= maxFrameCount) {
            if (resultFrameTimes == maxFrameCount) {
                resultFrameTimes++;
            }
            mAvgCPUValue = String.format("%.2f", resultAVGValue);
            resultFrameTimes = 0;
            resultAVGValue = 0;
            return;
        }
        resultFrameTimes++;
        double allResultTime = (resultFrameTimes - 1) * resultAVGValue;
        resultAVGValue = (allResultTime + resultTime) / resultFrameTimes;
    }
}
