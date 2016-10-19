/*
 *
 * This file is part of INDIserver.
 *
 * INDIserver is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * INDIserver is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with INDIserver.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2012 Alexander Tuschen <atuschen75 at gmail dot com>
 *
 */

package com.example.derri.samsungtv;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbRequest;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;

/**
 * PL2303 USB Serial Converter Driver for
 * devices with Android usbhost-support (3.2 upwards)
 * Based on pl2303.c from linux sources:
 * http://lxr.free-electrons.com/source/drivers/usb/serial/pl2303.c
 * 
 * Supports PL2303 and newer PL2303HX-types (both tested) 
 * with ProductID 0x2303 and VendorID 0x067b
 * 
 * TODO: support 3rd party USB IDs (see linux driver)
 * 
 * Supports selection of one of multiple connected converters
 * TODO: At the moment there is no way to identify a specific converter 
 * (i.e. by serial-number, because most PL2303 don't have a unique S/N).
 * The Android-USB-API just numbers the converters in the order they 
 * were plugged in (first-plugged, second-plugged, third-plugged...)  
 * 
 * Supports basic RTS/CTS FlowControl 
 * TODO: add RFR/CTS, DTR/DSR and XON/XOFF FlowControl
 * 
 * @author atuschen75 at gmail dot com*

 */
public class PL2303driver implements Runnable {
	
	// Variable for ApplicationContext necessary because UsbManager and PermissionIntent require it
	private Context AppContext;
	
	// Variables for all USB Classes
	private UsbManager mUsbManager;
	private UsbDevice mDevice;
	private UsbDeviceConnection mConnection;
	private UsbInterface intf;
	private UsbEndpoint ep0;
	private UsbEndpoint ep1;
	private UsbEndpoint ep2;
	
	// Variable ArrayList of all PL2303 converters connected
	private ArrayList<UsbDevice> pl2303ArrayList = new ArrayList<UsbDevice>();
	
	// Variable for Serial Port settings like Baudrate, see setup() 
	private byte[] PortSetting = new byte[7]; 
	
	// Variable for Status of RTC/CTS FlowControl
	private FlowControl Flow = FlowControl.OFF;
	
	// Variable for Status of DTR/RTS Lines
	private int ControlLines = 0;
	
	// Variable for Status of DSR/CTS/DCD/RI Lines
	private byte StatusLines = 0;
	
	// Variable indicating Type 0 = PL2303, Type 1 = PL2303-HX
	private int PL2303type = 0; 			

	// Callback Class interface variable
	private PL2303callback pl2303Callback; 	
	
	// Enumerate Baudrate constant
	public static enum BaudRate {
		B0, 
		B75,
		B150,
		B300,
		B600,
		B1200,
		B1800,
		B2400,
		B4800,
		B9600,
		B19200,
		B38400,
		B57600,
		B115200,
		B230400,
		B460800,
		B614400,
		B921600,
		B1228800,
		B2457600,
		B3000000,
		B6000000
	};

	// Enumerate Databit constant
	public static enum DataBits {
		D5,
		D6,
		D7,
		D8
	};

	// Enumerate Stopbit constant
	public static enum StopBits {
		S1,
		S2
	};

	// Enumerate Parity constant
	public static enum Parity {
		NONE,
		ODD,
		EVEN
	};
	
	// Enumerate Flowcontrol constant
	public static enum FlowControl {
		OFF,
		RTSCTS,
		RFRCTS,	// not yet implemented
		DTRDSR,	// not yet implemented
		XONXOFF	// not yet implemented
	};

	// USB control command constants
	private static final int SET_LINE_REQUEST_TYPE = 0x21;
	private static final int SET_LINE_REQUEST = 0x20;
	private static final int BREAK_REQUEST_TYPE = 0x21;
	private static final int BREAK_REQUEST = 0x23;	
	private static final int BREAK_OFF = 0x0000;
	private static final int GET_LINE_REQUEST_TYPE = 0xa1;
	private static final int GET_LINE_REQUEST = 0x21;
	private static final int VENDOR_WRITE_REQUEST_TYPE = 0x40;
	private static final int VENDOR_WRITE_REQUEST = 0x01;
	private static final int VENDOR_READ_REQUEST_TYPE = 0xc0;
	private static final int VENDOR_READ_REQUEST = 0x01;
	private static final int SET_CONTROL_REQUEST_TYPE = 0x21;
	private static final int SET_CONTROL_REQUEST = 0x22;
	
	// RS232 Line constants
	private static final int CONTROL_DTR = 0x01;
	private static final int CONTROL_RTS = 0x02;
	private static final int UART_DCD = 0x01;
	private static final int UART_DSR = 0x02;
	private static final int UART_RING = 0x08;
	private static final int UART_CTS = 0x80;

	// Tag constant for Log.d function
	private static final String TAG = "pl2303";
	
	// Action constant for PendingIntent 
	private static final String ACTION_USB_PERMISSION 	=   "com.android.hardware.USB_PERMISSION";

	/**
	 * BraodcastReceiver for permission to use USB-Device
	 * This is called by Android after the user has denied/granted access to a USB-Device
	 * 
	 */
	private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
		public void onReceive(Context context, Intent intent) {
			
			// Check if the action is from "our" intent
			if (ACTION_USB_PERMISSION.equals(intent.getAction())) {
				synchronized (this) {
					
					// Get the selected PL2303 from the intent 
					UsbDevice device = (UsbDevice)intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
					
					// Check if permission was granted or not
					if ((intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) && (device != null)) {
						
						// The user has allowed access to the PL2303
						Log.d(TAG, "Permission granted for device " + device.getDeviceName());
						
						// Now try to initialize the selected PL2303
						if (initalize(device)){
							Log.d(TAG, "Device successfully initialized");
							pl2303Callback.onInitSuccess();
						} else {
							Log.d(TAG, "Device initialization failed");
							pl2303Callback.onInitFailed("Device initialization failed");
							close();
						}
						
					} else {
						
						// The user denied access
						mDevice = null;
						Log.d(TAG, "Permission denied for device " + device.getDeviceName());
						pl2303Callback.onInitFailed("Permission denied");
						
					}
				}
			}
		}
	};

	/**
	 * Constructor
	 * @param context Application Context
	 * @param PL2303callback Object which implements the callback methods
	 */
	public PL2303driver(Context context, PL2303callback callback) {
		Log.d(TAG, "PL2303 driver starting");
		AppContext = context; // Store ApplicationContext in local variable
		pl2303Callback = callback; // Store the callback-object in local variable
		mUsbManager = (UsbManager) AppContext.getSystemService(Context.USB_SERVICE); // get a UsbManager

		// Register BroadcastReceiver for Permission Intent 
		IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
		AppContext.registerReceiver(mUsbReceiver, filter); 
	}
	
	/**
	 * Get a list of pl2303 devices currently connected. Must be called before open().
	 * @return ArrayList<UsbDevice> 
	 */
	public ArrayList<UsbDevice> getDeviceList() {
		pl2303ArrayList.clear();
		
		// Get the USB device list (of all devices)
		HashMap<String, UsbDevice> deviceList = mUsbManager.getDeviceList();
				
		// Scan the devices and copy all PL2303-Adaptors into the pl2303ArrayList
		Iterator<UsbDevice> deviceIterator = deviceList.values().iterator();
		while(deviceIterator.hasNext()){
			UsbDevice device = deviceIterator.next();
			if ((device.getProductId()==0x2303) && (device.getVendorId()==0x067b)) {
			pl2303ArrayList.add(device); }
		}
		Log.d(TAG, pl2303ArrayList.size()+" device(s) found");
 
		return pl2303ArrayList;
	}
	
	/**
	 * Open USB-Connection to a device (after getDeviceList() has been called)
	 * @param UsbDevice
	 * @throws IOException if device is not PL2303 or was not in the original getDeviceList()
	 */
	public void open(UsbDevice device) throws IOException {
		
		// just some validity checks 
		if (pl2303ArrayList.isEmpty()) throw new IOException("No devices connected, or getDeviceList() was not called");
		if (!pl2303ArrayList.contains(device)) throw new IOException("Device not in original list");
		if ((device.getProductId()!=0x2303) && (device.getVendorId()!=0x067b)) throw new IOException("Not a compatible PL2303-device");
		
		// Create an Intent to request permission to use a USB-Device
		PendingIntent mPermissionIntent;
		mPermissionIntent = PendingIntent.getBroadcast(AppContext, 0, new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_ONE_SHOT);

		// Request the permission (fire the Intent) to use the device from the user 
		mUsbManager.requestPermission(device, mPermissionIntent);
		Log.d(TAG, "Requesting permission to use " + device.getDeviceName());
	}
		
	/**
	 * initialize the PL2303 converter
	 * called by BroadcastReceiver, after user granted access to the device
	 * @return true on success
	 */
	private boolean initalize(UsbDevice device) {
		mDevice = device;
		Log.d(TAG, "Device Name: "+mDevice.getDeviceName());
		Log.d(TAG, "VendorID: "+mDevice.getVendorId());
		Log.d(TAG, "ProductID: "+mDevice.getProductId());
		
		// get a USB interface
		intf = mDevice.getInterface(0);
		if (intf == null) {
			Log.e(TAG, "Getting interface failed!");
			return false;
		}
		
		// endpoint addr 0x81 = input interrupt
		ep0 = intf.getEndpoint(0); 
		if ((ep0.getType() != UsbConstants.USB_ENDPOINT_XFER_INT) || (ep0.getDirection() != UsbConstants.USB_DIR_IN)) {
			Log.e(TAG, "Getting endpoint 0 (control) failed!");
			return false;
		}
		
		// endpoint addr 0x82 = output bulk
		ep1 = intf.getEndpoint(1); 
		if ((ep1.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) || (ep1.getDirection() != UsbConstants.USB_DIR_OUT)) {
			Log.e(TAG, "Getting endpoint 1 (output) failed!");
			return false;
		}
		
		// endpoint addr 0x83 = input bulk
		ep2 = intf.getEndpoint(2); 
		if ((ep2.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) || (ep2.getDirection() != UsbConstants.USB_DIR_IN)) {
			Log.e(TAG, "Getting endpoint 2 (input) failed!");
			return false;
		}
		
		// get a USB connection
		UsbDeviceConnection connection = mUsbManager.openDevice(mDevice);
		if (connection == null) {
			Log.e(TAG, "Getting DeviceConnection failed!");
			return false;
		}
		
		// claim exclusive access, so that no other app can use this interface/device
		if (!connection.claimInterface(intf, true)) {
			Log.e(TAG, "Exclusive interface access failed!");
			return false;
		}
		
		// save to local variable
		mConnection = connection;

		// Check for HX-Type of PL2303 
		if (mConnection.getRawDescriptors()[7] == 64) PL2303type = 1; //Type 1 = PL2303HX
		Log.d(TAG, "PL2303 type " +PL2303type+ " detected");
		
		// Initialization of PL2303 according to linux pl2303.c driver
		byte[] buffer = new byte[1];
		mConnection.controlTransfer(VENDOR_READ_REQUEST_TYPE, VENDOR_READ_REQUEST, 0x8484, 0, buffer, 1, 100);
		mConnection.controlTransfer(VENDOR_WRITE_REQUEST_TYPE, VENDOR_WRITE_REQUEST, 0x0404, 0, null, 0, 100);
		mConnection.controlTransfer(VENDOR_READ_REQUEST_TYPE, VENDOR_READ_REQUEST, 0x8484, 0, buffer, 1, 100);
		mConnection.controlTransfer(VENDOR_READ_REQUEST_TYPE, VENDOR_READ_REQUEST, 0x8383, 0, buffer, 1, 100);
		mConnection.controlTransfer(VENDOR_READ_REQUEST_TYPE, VENDOR_READ_REQUEST, 0x8484, 0, buffer, 1, 100);
		mConnection.controlTransfer(VENDOR_WRITE_REQUEST_TYPE, VENDOR_WRITE_REQUEST, 0x0404, 1, null, 0, 100);
		mConnection.controlTransfer(VENDOR_READ_REQUEST_TYPE, VENDOR_READ_REQUEST, 0x8484, 0, buffer, 1, 100);
		mConnection.controlTransfer(VENDOR_READ_REQUEST_TYPE, VENDOR_READ_REQUEST, 0x8383, 0, buffer, 1, 100);
		mConnection.controlTransfer(VENDOR_WRITE_REQUEST_TYPE, VENDOR_WRITE_REQUEST, 0, 1, null, 0, 100);
		mConnection.controlTransfer(VENDOR_WRITE_REQUEST_TYPE, VENDOR_WRITE_REQUEST, 1, 0, null, 0, 100);
		if (PL2303type == 1) mConnection.controlTransfer(VENDOR_WRITE_REQUEST_TYPE, VENDOR_WRITE_REQUEST, 2, 0x44, null, 0, 100);
		else mConnection.controlTransfer(VENDOR_WRITE_REQUEST_TYPE, VENDOR_WRITE_REQUEST, 2, 0x24, null, 0, 100);
		
		// Start control thread (Runnable at the end of this file) for status lines DSR,CTS,DCD and RI
		Thread t = new Thread(this);
		t.start();
		
		return true;
	}

	/**
	 *  Close the connection
	 */
	public void close() {
		if (mConnection != null) {
			// drop DTR/RTS
			setRTS(false);
			setDTR(false);
			
			mConnection.releaseInterface(intf);
			mConnection.close();
			mConnection = null;
			mDevice = null;
			ep0 = null;
			ep1 = null;
			ep2 = null;
			Log.d(TAG, "Device closed");
		}
	}

	/**
	 * Are we connected to a pl2303 converter?
	 * This is only the USB connection, not the serial connection.
	 * 
	 * @return true on connection
	 */
	public boolean isConnected() {
		if (mConnection != null) return true;
		else return false;
	}

	/**
	 * Setup basic communication parameters according to linux pl2303.c driver 
	 * @param Enum BaudRate 
	 * @param Enum DataBits
	 * @param Enum StopBits
	 * @param Enum Parity
	 * @param Enum FlowControl
	 * @throws IOException if settings not supported or connection is closed
	 */
	public void setup(BaudRate R, DataBits D, StopBits S, Parity P, FlowControl F) throws IOException {
		
		if (mConnection == null) throw new IOException("Connection closed");

		// Get current settings
		mConnection.controlTransfer(GET_LINE_REQUEST_TYPE, GET_LINE_REQUEST, 0, 0, PortSetting, 7, 100);
		mConnection.controlTransfer(VENDOR_WRITE_REQUEST_TYPE, VENDOR_WRITE_REQUEST, 0, 1, null, 0, 100);
		Log.d(TAG, "Current serial configuration:" + PortSetting[0] + "," + PortSetting[1] + "," + PortSetting[2] + "," + PortSetting[3] + "," + PortSetting[4] + "," + PortSetting[5] + "," + PortSetting[6]);

		// Setup Baudrate
		int baud;
		switch (R) {
		case B0: baud = 0; break;
		case B75: baud = 75; break;
		case B150: baud = 150; break;
		case B300: baud = 300; break;
		case B600: baud = 600; break;
		case B1200: baud = 1200; break;
		case B1800: baud = 1800; break;
		case B2400: baud = 2400; break;
		case B4800: baud = 4800; break;
		case B9600: baud = 9600; break;
		case B19200: baud = 19200; break;
		case B38400: baud = 38400; break;
		case B57600: baud = 57600; break;
		case B115200: baud = 115200; break;
		case B230400: baud = 230400; break;
		case B460800: baud = 460800; break;
		case B614400: baud = 614400; break;
		case B921600: baud = 921600; break;
		case B1228800: baud = 1228800; break;
		// The following rates only for HX-Type of PL2303
		case B2457600: baud = 2457600; break;
		case B3000000: baud = 3000000; break;
		case B6000000: baud = 6000000; break;
		default: throw new IOException("Baudrate not supported");
		}

		if  ((baud > 1228800) && (PL2303type == 0)) throw new IOException("Baudrate not supported"); // Only PL2303HX supports the higher baudrates

		PortSetting[0]=(byte) (baud & 0xff);
		PortSetting[1]=(byte) ((baud >> 8) & 0xff);
		PortSetting[2]=(byte) ((baud >> 16) & 0xff);
		PortSetting[3]=(byte) ((baud >> 24) & 0xff);

		// Setup Stopbits
		switch (S) {
		case S1: PortSetting[4] = 0; break;
		case S2: PortSetting[4] = 2; break;
		default: throw new IOException("Stopbit setting not supported");
		}

		// Setup Parity
		switch (P) {
		case NONE: PortSetting[5] = 0; break;
		case ODD: PortSetting[5] = 1; break;
		case EVEN: PortSetting[5] = 2; break;
		default: throw new IOException("Parity setting not supported");
		}

		// Setup Databits
		switch (D) {
		case D5: PortSetting[6] = 5; break;
		case D6: PortSetting[6] = 6; break;
		case D7: PortSetting[6] = 7; break;
		case D8: PortSetting[6] = 8; break;
		default: throw new IOException("Databit setting not supported");
		}

		// Set new configuration on PL2303
		mConnection.controlTransfer(SET_LINE_REQUEST_TYPE, SET_LINE_REQUEST, 0, 0, PortSetting, 7, 100); 
		Log.d(TAG, "New serial configuration:" + PortSetting[0] + "," + PortSetting[1] + "," + PortSetting[2] + "," + PortSetting[3] + "," + PortSetting[4] + "," + PortSetting[5] + "," + PortSetting[6]);
		
		// Disable BreakControl
		mConnection.controlTransfer(BREAK_REQUEST_TYPE, BREAK_REQUEST, BREAK_OFF, 0, null, 0, 100);

		// Enable/Disable FlowControl
		switch (F) {
		case OFF:
			mConnection.controlTransfer(VENDOR_WRITE_REQUEST_TYPE, VENDOR_WRITE_REQUEST, 0x0, 0x0, null, 0, 100);
			setRTS(false);
			setDTR(false);
			Flow = F;
			Log.d(TAG, "FlowControl disabled");
			break;
			
		case RTSCTS: 
			if (PL2303type == 1) mConnection.controlTransfer(VENDOR_WRITE_REQUEST_TYPE, VENDOR_WRITE_REQUEST, 0x0, 0x61, null, 0, 100);
			else mConnection.controlTransfer(VENDOR_WRITE_REQUEST_TYPE, VENDOR_WRITE_REQUEST, 0x0, 0x41, null, 0, 100);
			setDTR(true);
			setRTS(true);
			Flow = F;
			Log.d(TAG, "RTS/CTS FlowControl enabled");
			break;
		
		case RFRCTS: break;
		case DTRDSR: break;
		case XONXOFF: break;
		}
		
	}

	/**
	 * Switch DTR on or off
	 * @param state
	 */
	public void setDTR(boolean state) {
		if ((state) && !((ControlLines & CONTROL_DTR)==CONTROL_DTR)) ControlLines = ControlLines + CONTROL_DTR;
		if (!(state) && ((ControlLines & CONTROL_DTR)==CONTROL_DTR)) ControlLines = ControlLines - CONTROL_DTR;
		mConnection.controlTransfer(SET_CONTROL_REQUEST_TYPE, SET_CONTROL_REQUEST, ControlLines , 0, null, 0, 100);
		Log.d(TAG, "DTR set to " + state);
	}
	
	/**
	 * Switch RTS on or off
	 * @param state
	 */
	public void setRTS(boolean state) {
		if ((state) && !((ControlLines & CONTROL_RTS)==CONTROL_RTS)) ControlLines = ControlLines + CONTROL_RTS;
		if (!(state) && ((ControlLines & CONTROL_RTS)==CONTROL_RTS)) ControlLines = ControlLines - CONTROL_RTS;
		mConnection.controlTransfer(SET_CONTROL_REQUEST_TYPE, SET_CONTROL_REQUEST, ControlLines , 0, null, 0, 100);
		Log.d(TAG, "RTS set to " + state);
	}
	
	
	/** 
	 * Get the InputStream of the connection. 
	 * Supported functions:
	 * 
	 * read() - blocking read (only one byte) 
	 * read(byte[], int, int) - non-blocking read (any size)
	 * 
	 * @return InputStream if connected else null
	 */
	public InputStream getInputStream() {
		if (mConnection != null) {
			InputStream in = new InputStream() {

				// Blocking read (Timeout set to 0)
				@Override
				public int read() throws IOException {
					synchronized (this) {
						int retVal= -1;
						if (mConnection == null) throw new IOException("Connection closed");
						
						// If FlowControl: Check DSR before read
						if ((Flow== FlowControl.RTSCTS) && ((StatusLines & UART_DSR) != UART_DSR)) throw new IOException("DSR down");
						
						byte [] readBuffer = new byte[1];
						int bytesRead = mConnection.bulkTransfer(ep2, readBuffer, 1, 0);
						if (bytesRead > 0) retVal = readBuffer[0];
						return retVal;
					}
				}

				// Non-blocking read (Timeout set to 100ms)
				@Override
				public int read(byte[] buffer, int offset, int length) throws IOException, IndexOutOfBoundsException {
					synchronized (this) {
						int PacketSize = ep2.getMaxPacketSize();
						int totalBytesRead = 0;
						byte [] readBuffer = new byte[PacketSize];
						
						if ((offset < 0) || (length < 0) || ((offset + length) > buffer.length)) throw new IndexOutOfBoundsException();
						if (mConnection == null) throw new IOException("Connection closed");
						
						// Max Packet Size 64 bytes! Split larger read-requests in multiple bulk-transfers
						int numTransfers = length / PacketSize;
						if (length % PacketSize > 0) numTransfers++;
						
						for (int i = 0 ; i < numTransfers ; i++) {
							// If FlowControl: Check DSR before read
							if ((Flow == FlowControl.RTSCTS) && ((StatusLines & UART_DSR) != UART_DSR)) throw new IOException("DSR down");
							int bytesRead = mConnection.bulkTransfer(ep2, readBuffer, PacketSize, 100);
							
							if (bytesRead >= 0) {
								System.arraycopy(readBuffer, 0, buffer, offset, bytesRead);
								offset = offset+bytesRead;
								totalBytesRead = totalBytesRead + bytesRead;
							} else break;
							
						}
						return totalBytesRead;	
					}
				}
			};
			return in;
		} else return null;
	}

	/** 
	 * Get the OutputStream of the connection. 
	 * Supported functions:
	 * 
	 * public void write(int) - blocking write (only one byte) 
	 * public void write(byte[], int, int) - non-blocking write (any size)
	 * 
	 * @return OutputStream if connected else null
	 */
	public OutputStream getOutputStream() {
		if (mConnection != null) {
			OutputStream out = new OutputStream() {
				
				// Blocking write (Timeout set to 0)
				@Override
				public void write(int oneByte) throws IOException {
					synchronized (this) {
						if (mConnection == null) throw new IOException("Connection closed");
						
						// If FlowControl: Check DSR / CTS before write
						if (Flow== FlowControl.RTSCTS) {
							if ((StatusLines & UART_DSR) != UART_DSR) throw new IOException("DSR down");

							// Wait until CTS is up
							// TODO: this blocks!
							while ((StatusLines & UART_CTS) != UART_CTS) {
								 try {
									Thread.sleep(100);
								} catch (InterruptedException e) {
								}
							}
						}
						
						byte [] writeBuffer = new byte[1];
						int bytesWritten = mConnection.bulkTransfer(ep1, writeBuffer, 1, 0);
						if (bytesWritten < 1 ) throw new IOException("BulkWrite failed - written: "+bytesWritten);
					}
				}

				// Non-blocking write (Timeout set to 100ms)
				@Override
				public void write (byte[] buffer, int offset, int count) throws IOException, IndexOutOfBoundsException {
					synchronized (this) {
						int PacketSize = ep1.getMaxPacketSize();
						byte [] writeBuffer = new byte[PacketSize];
						
						
						if ((offset < 0) || (count < 0) || ((offset + count) > buffer.length)) throw new IndexOutOfBoundsException();
						if (mConnection == null) throw new IOException("Connection closed");
						
						// Max Packet Size 64 bytes! Split larger write-requests in multiple bulk-transfers
						int numTransfers = count / PacketSize;
						if (count % PacketSize > 0) numTransfers++;
						
						for (int i=0;i<numTransfers;i++) {

							// If FlowControl: Check DSR /CTS before write
							if (Flow== FlowControl.RTSCTS) {
								if ((StatusLines & UART_DSR) != UART_DSR) throw new IOException("DSR down");

								// Wait until CTS is up
								// TODO: this blocks!
								while ((StatusLines & UART_CTS) != UART_CTS) {
									 try {
										Thread.sleep(100);
									} catch (InterruptedException e) {
									}
								}
							}
							
							offset = offset + (i * PacketSize);

							// If this is the last part of multiple transfers correct the PacketSize (might be smaller than maxPacketSize) 
							if (i == numTransfers - 1) PacketSize = count - ((numTransfers-1) * PacketSize); 
							
							System.arraycopy(buffer, offset, writeBuffer, 0, PacketSize);
							int bytesWritten = mConnection.bulkTransfer(ep1, writeBuffer, PacketSize, 100);
							if (bytesWritten != PacketSize) throw new IOException("BulkWrite failed - count: " + PacketSize + " written: "+bytesWritten);
								
						}
					}
				}
			};
			return out;
		} else return null;
	}

	/**
	 * Runnable for detection of DSR, CTS , DCD and RI
	 * Calls the appropriate Callback-function on status change
	 * UsbRequest on Endpoint zero returns 10 bytes. Byte 9 contains the line status. 
	 */
	@Override
	public void run() {
		ByteBuffer readBuffer = ByteBuffer.allocate(ep0.getMaxPacketSize());
		UsbRequest request = new UsbRequest();
		
		// Although documentation says that UsbRequest doesn't work on Endpoint 0 it actually works  
		request.initialize(mConnection, ep0);

		while (mConnection != null) {
			request.queue(readBuffer, ep0.getMaxPacketSize());
			UsbRequest retRequest = mConnection.requestWait();
			
			// The request returns when any line status has changed
			if (retRequest.getEndpoint()==ep0) {
				
				if ((readBuffer.get(8) & UART_DSR) != (StatusLines & UART_DSR)) {
					Log.d(TAG,"Change on DSR detected: "+(readBuffer.get(8) & UART_DSR));
					if ((readBuffer.get(8) & UART_DSR) == UART_DSR) pl2303Callback.onDSR(true);
					else pl2303Callback.onDSR(false);
				}
				
				if ((readBuffer.get(8) & UART_CTS) != (StatusLines & UART_CTS)) {
					Log.d(TAG,"Change on CTS detected: "+(readBuffer.get(8) & UART_CTS));
					if ((readBuffer.get(8) & UART_CTS) == UART_CTS) pl2303Callback.onCTS(true);
					else pl2303Callback.onCTS(false);
				}
				
				if ((readBuffer.get(8) & UART_DCD) != (StatusLines & UART_DCD)) {
					Log.d(TAG,"Change on DCD detected: "+(readBuffer.get(8) & UART_DCD));
					if ((readBuffer.get(8) & UART_DCD) == UART_DCD) pl2303Callback.onDCD(true);
					else pl2303Callback.onDCD(false);
				}
				
				if ((readBuffer.get(8) & UART_RING) != (StatusLines & UART_RING)) {
					Log.d(TAG,"Change on RI detected: "+(readBuffer.get(8) & UART_RING));
					if ((readBuffer.get(8) & UART_RING) == UART_RING) pl2303Callback.onRI(true);
					else pl2303Callback.onRI(false);
				}
			}
			
			// Save status
			StatusLines = readBuffer.get(8);
		}
	} 
	
}
