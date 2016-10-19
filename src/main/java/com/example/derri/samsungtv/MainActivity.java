package com.example.derri.samsungtv;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.net.ConnectivityManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Bundle;

import com.example.derri.samsungtv.PL2303driver.BaudRate;
import com.example.derri.samsungtv.PL2303driver.DataBits;
import com.example.derri.samsungtv.PL2303driver.FlowControl;
import com.example.derri.samsungtv.PL2303driver.Parity;
import com.example.derri.samsungtv.PL2303driver.StopBits;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;
import java.util.Timer;
import java.util.TimerTask;

import android.app.Activity;
import android.os.Handler;
import android.os.Message;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

public class MainActivity extends Activity implements PL2303callback, Runnable {

    private InputStreamReader inReader;
    private DataOutputStream outWriter;
    public ArrayList<UsbDevice> deviceList;

    private static final byte[] PWR_ON = new byte[]{(byte) 0xAA, (byte) 0x11, (byte) 0xFE,
            (byte) 0x01, (byte) 0x01, (byte) 0x11};
    private static final byte[] PWR_OFF = new byte[]{(byte) 0xAA, (byte) 0x11, (byte) 0xFE,
            (byte) 0x01, (byte) 0x00, (byte) 0x10};
    private static final byte[] getPwr = new byte[]{(byte) 0xAA, (byte) 0x11, (byte) 0xFE,
            (byte) 0x00, (byte) 0x0F};
    private static final String CONFIG = "/mnt/usb_storage/USB_DISK2/text/SettingURL.cfg";
    private static final String SERVER = "/mnt/usb_storage/USB_DISK2/setting.txt";
    private static final String USER_AGENT = "Mozilla/5.0";

    String GetWakeUpTime = "00:00:00";
    String GetSleepTime = "00:00:00";

    int delay = 30000;
    boolean deviceOn = false;

    PL2303driver Device;

    Button powerOn;
    Button powerOff;
    Button powerState;
    TextView log;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Create a new instance of the PL2303 driver with local ApplicationContext and callbacks)
        Device = new PL2303driver(getApplicationContext(), this);

        // Get a List of all PL2303 currently connected
        deviceList = Device.getDeviceList();

        // If there are PL2303 present, try to open a connection
        if (!deviceList.isEmpty()) {
            try {
                Device.open(deviceList.get(0));
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
        powerOn = (Button) findViewById(R.id.PowerOn);
        powerOff = (Button) findViewById(R.id.PowerOff);
        powerState = (Button) findViewById(R.id.powerState);
        powerOn.setOnClickListener(onPowerOnClickListener);
        powerOff.setOnClickListener(onPowerOffClickListener);
        powerState.setOnClickListener(onPowerStateClickListener);
        log = (TextView) findViewById(R.id.textView);
    }

    // Only runs if the initial setup is successful

    @Override
    public void onInitSuccess() {
        try {
            // Setup all serial parameters
            Device.setup(BaudRate.B9600, DataBits.D8, StopBits.S1, Parity.NONE, FlowControl.OFF);
            log.append("\nSetup success");
            sendHttp(getServerInfo(), "USBDeviceOK");
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        inReader = new InputStreamReader(Device.getInputStream());
        outWriter = new DataOutputStream(Device.getOutputStream());
        // Start the receive-thread
        Thread t = new Thread(this);
        t.start();
        startSchedule();
        getServerInfo();
    }

    /**
     *  Handler to receive messages from the InputStreamReader. This is necessary as sending
     *  messages directly to the log from run() will crash the app.
     */
    private final Handler handler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            String text = msg.getData().getString("TEXT");
            if (text.length() > 2) {
                log.append("\nResponse from TV: " + msg.getData().getString("TEXT"));
                log.append("\nTV IS ON");
            }
            else if (text.length() != 0) {
                log.append("\nTV IS OFF");
            }

        }
    };

    /**
     *  Main scheduling method. Uses a buffered reader to read the SettingURL.cfg file and gets
     *  the Wake and Sleep Time, then turns the TV on or off based on the time. This method uses a
     *  handler to repeat on a set delay in order to check for server updates. This will not turn
     *  off the TV if it is on but scheduled to be off when the program is started, since
     *  getPowerState() is not working.
     */
    private void startSchedule() {
        final Handler h = new Handler();
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    BufferedReader br = new BufferedReader(new FileReader(CONFIG));
                    String line;
                    while ((line = br.readLine()) != null) {
                        int wakeIndex = line.indexOf("GetWakeUpTime=");
                        int sleepIndex = line.indexOf("GetSleepTime=");
                        if (wakeIndex != -1) {
                            line = line.replace("GetWakeUpTime=", "");
                            GetWakeUpTime = line;
                        } else if (sleepIndex != -1) {
                            line = line.replace("GetSleepTime=", "");
                            GetSleepTime = line;
                        }
                    }
                    SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
                    sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
                    Calendar c = Calendar.getInstance();
                    long time = c.getTimeInMillis();
                    c.set(Calendar.HOUR_OF_DAY, 0);
                    c.set(Calendar.MINUTE, 0);
                    c.set(Calendar.SECOND, 0);
                    c.set(Calendar.MILLISECOND, 0);
                    long now = time - c.getTimeInMillis();

                    try {
                        Date onDate = sdf.parse(GetWakeUpTime);
                        Date offDate = sdf.parse(GetSleepTime);
                        long on = onDate.getTime();
                        long off = offDate.getTime();
                        log.append("\nNow: " + now);
                        log.append(" On: " + Long.toString(on));
                        log.append(" Off: " + Long.toString(off));
                        if (now > on && now < off && !deviceOn) {
                            log.append("\nTime to turn on device");
                            sendPowerOn();
                        } else if (now < on && now > off && deviceOn) {
                            log.append("\nTime to turn off device");
                            sendPowerOff();
                        } else if (now > off && off > on && deviceOn) {
                            log.append("\nTime to turn off device");
                            sendPowerOff();
                        } else if (now > on && on > off && !deviceOn) {
                            log.append("\nTime to turn on device");
                            sendPowerOn();
                        }
                    } catch (ParseException e) {
                    }

                } catch (IOException e) {
                    //slit wrists
                }
                h.postDelayed(this, delay);
            }
        }, 0);
    }

    /*
 * Load file content to String
 */
    public static String loadFileAsString(String filePath) throws java.io.IOException{
        StringBuffer fileData = new StringBuffer(1000);
        BufferedReader reader = new BufferedReader(new FileReader(filePath));
        char[] buf = new char[1024];
        int numRead=0;
        while((numRead=reader.read(buf)) != -1){
            String readData = String.valueOf(buf, 0, numRead);
            fileData.append(readData);
        }
        reader.close();
        return fileData.toString();
    }

    /*
     * Get the STB MacAddress
     */
    public String getMacAddress(){
        try {
            return loadFileAsString("/sys/class/net/eth0/address")
                    .toUpperCase().substring(0, 17);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     *  Retrieves the devices mac address and the http server's information from setting.txt file
     *
     * @return Returns an array containing the mac address, name of device, server url, and time.
     */
    private String[] getServerInfo() {
        String name = "";
        String url = "";
        String time = "";
        String macAddress = getMacAddress();

        Calendar cal = Calendar.getInstance();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        time = sdf.format(cal.getTime());

        try {
            BufferedReader br = new BufferedReader(new FileReader(SERVER));
            String line;
            while ((line = br.readLine()) != null) {
                int nameIndex = line.indexOf("name");
                int urlIndex = line.indexOf("url");
                if (nameIndex != -1) {
                    String[] split = line.split("\"");
                    name = split[3];
                }
                if (urlIndex != -1) {
                    String[] split = line.split("\"");
                    url = split[3];
                }
            }
        } catch (IOException e) {
            log.append("Settings file not found");
        }
        return new String[]{macAddress, name, url, time};
    }

    /**
     * Receives the server info and firmware to send, then creates a new HttpRequest class, which
     * executes the Http GET command in an AsyncTask. Sending http commands on the main thread will
     * crash the app, as http requests cannot be executed on the main thread.
     *
     * @param info the server info retrieved from getServerInfo()
     * @param firmware the command to be sent to the server
     */
    private void sendHttp(String[] info, String firmware) {
        String macAddress = info[0];
        String name = info[1];
        String url = info[2];
        String time = info[3];
        String[] split = time.split(" ");
        String http = "http://" + url + "/WS.php";
        String urlParameters  = "Cmd=RegisterPlayer&PlayerID=" + name + "&PlayerPwd=&MacAddr=" +
                macAddress + "&DisplayFormat=&CurrentTime=" + split[0] + "%20" + split[1] +
                "&Firmware=" + firmware;
        HttpRequest httpRequest = new HttpRequest();
        httpRequest.execute(http, urlParameters);
    }

    /**
     * Not working at the moment. Sends the getPwr byte array to the outWriter, which requests a
     * power state status update from the TV. The byte array sends successfully, but the TV does
     * not give a response for some reason.
     */
    private void getPowerState() {
        try {
            outWriter.write(getPwr);
            outWriter.flush();
            sendHttp(getServerInfo(), "PwrState");
            log.append("\nSent: Get Power State");
        } catch (IOException e) {
            log.append("\nError sending getPwr");
        }
    }

    // Sends the PWR_ON byte array which turns the TV on. Sets deviceOn boolean flag to true.
    private void sendPowerOn() {
        try {
            outWriter.write(PWR_ON);
            outWriter.flush();
            deviceOn = true;
            sendHttp(getServerInfo(), "TVon");
            log.append("\nSent: Power ON");
        } catch (IOException e) {
            log.append("\nError sending PWR_ON");
        }
    }

    /**
     * Sends the PWR_OFF byte array, sleeps for 500 ms, then sends the PWR_OFF byte array again.
     * The array is sent twice because the TV sometimes does not receive the first command.
     * Sets the deviceOn boolean flag to false.
     */
    private void sendPowerOff() {
        try {
            outWriter.write(PWR_OFF);
            outWriter.flush();
            Thread.sleep(500);
            outWriter.write(PWR_OFF);
            outWriter.flush();
            deviceOn = false;
            sendHttp(getServerInfo(), "TVoff");
            log.append("\n Sent: Power OFF");
        } catch (IOException|InterruptedException e) {
            log.append("\nError sending PWR_OFF");
        }
    }

    private final View.OnClickListener onPowerOnClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            sendPowerOn();
        }
    };

    private final View.OnClickListener onPowerOffClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            sendPowerOff();
        }
    };

    private final View.OnClickListener onPowerStateClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            getPowerState();
        }
    };

    /* Callback routine for PL2303 if init failed
     *
     */
    @Override
    public void onInitFailed(String reason) {
        //dosomething
    }

    /* Callback routine for PL2303 if status of RI changed
     *
     */
    @Override
    public void onRI(boolean state) {
        //dosomething
    }

    /* Callback routine for PL2303 if status of DCD changed
     *
     */
    @Override
    public void onDCD(boolean state) {
        //dosomething
    }

    /* Callback routine for PL2303 if status of DSR changed
     *
     */
    @Override
    public void onDSR(boolean state) {
        //dosomething
    }

    /* Callback routine for PL2303 if status of CTS changed
     *
     */
    @Override
    public void onCTS(boolean state) {
        //dosomething
    }

    /**
     *  Constantly reads input from the TV and decodes the hex string to a readable format. Sends
     *  the message to the handler to be added to the log.
     */
    public void run() {
        while (Device.isConnected()) {
            String str = "";
            char[] readBuffer = new char[100];
            StringBuffer hex = new StringBuffer();
            int data;
            int len = 0;
            try {
                data = inReader.read();
                if (data != -1) {
                    len++;
                    while (data != -1) {
                        len++;
                        try {
                            data = inReader.read(readBuffer);
                        }
                        catch(IOException e) {
                        }
                    }

                    for (int i=0; i<len;i++) {
                        hex.append(Integer.toHexString((int)readBuffer[i]));
                    }
                    str = hex.toString();
                }
            }
            catch(IOException e) {
            }

            Message msg = handler.obtainMessage();
            Bundle dat = new Bundle();

            dat.putString("TEXT", str);
            msg.setData(dat);
            handler.sendMessage(msg);
        }
    }

    /**
     *  Class to send the http request to the server. Also receives the response from the server
     *  and updates the log with the server's response. Nested class so that the log can still be
     *  accessed from AsyncTask. Necessary since network commands cannot be executed on the main
     *  thread.
     */
    class HttpRequest extends AsyncTask<String, Void, String> {

        @Override
        protected String doInBackground(String... params) {
            String result = "";
            try {
                String url = params[0] + "?" + params[1];
                URL get = new URL(url);

                HttpURLConnection conn = (HttpURLConnection) get.openConnection();
                conn.setDoOutput(true);
                conn.setInstanceFollowRedirects(false);
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Content-Type", "text/plain");
                conn.setRequestProperty("charset", "utf-8");
                conn.connect();
            /*OutputStreamWriter request = new OutputStreamWriter(conn.getOutputStream());
            request.write(urlParameters);
            request.flush();
            request.close();*/

                BufferedReader bufferedReader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream()));
                String inputLine;
                StringBuilder response = new StringBuilder();
                while ((inputLine = bufferedReader.readLine()) != null) {
                    response.append(inputLine);
                }
                result = response.toString();
                bufferedReader.close();

            } catch (IOException e) {
                return null;
            }
            return result;
        }

        protected void onPostExecute(String result) {
            log.append("\n" + result);
        }
    }
}
