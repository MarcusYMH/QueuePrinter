package com.example.queueprinter;

import android.Manifest;
import android.app.AlertDialog;
import android.bluetooth.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.NumberPicker;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends AppCompatActivity {

    // UI
    private TextView tvPrinter;

    // State
    private SharedPreferences prefs;
    private int c1 = 0, c2 = 0;          // 当前号
    private int last1 = 0, last2 = 0;    // 最近打印号
    private String savedMac = null;

    // Bluetooth
    private BluetoothAdapter adapter;
    private BluetoothDevice currentPrinter;

    private final int REQ_BT_PERMS = 1001;

    // 扫描列表
    private final List<BluetoothDevice> scanList = new ArrayList<>();
    private ArrayAdapter<String> scanAdapter;

    private final BroadcastReceiver discoveryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null && device.getName() != null && !containsDevice(device)) {
                    scanList.add(device);
                    if (scanAdapter != null) {
                        scanAdapter.add(device.getName() + " (" + device.getAddress() + ")");
                        scanAdapter.notifyDataSetChanged();
                    }
                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                Toast.makeText(MainActivity.this, "Scan finished", Toast.LENGTH_SHORT).show();
            }
        }
    };

    private boolean containsDevice(BluetoothDevice d) {
        for (BluetoothDevice x : scanList) {
            if (x.getAddress().equals(d.getAddress())) return true;
        }
        return false;
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvPrinter = findViewById(R.id.tvPrinter);
        prefs = getSharedPreferences("queue_prefs", MODE_PRIVATE);
        c1 = prefs.getInt("c1", 0);
        c2 = prefs.getInt("c2", 0);
        last1 = prefs.getInt("last1", 0);
        last2 = prefs.getInt("last2", 0);
        savedMac = prefs.getString("printer_mac", null);

        adapter = BluetoothAdapter.getDefaultAdapter();

        // 恢复打印机名
        if (savedMac != null && adapter != null) {
            try {
                currentPrinter = adapter.getRemoteDevice(savedMac);
                tvPrinter.setText("Printer: " + currentPrinter.getName() + " (" + savedMac + ")");
            } catch (Exception e) {
                tvPrinter.setText("Printer: Saved (" + savedMac + ")");
            }
        }

        // 权限
        ensureBtPermissions();

        // 注册扫描广播
        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_FOUND);
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        registerReceiver(discoveryReceiver, f);

        // 按钮绑定
        Button btnConnect = findViewById(R.id.btnConnect);
        btnConnect.setOnClickListener(v -> openPrinterPicker());

        findViewById(R.id.btnC1Next).setOnClickListener(v -> { c1++; last1 = c1; save(); print("C1", c1); });
        findViewById(R.id.btnC1Reprint).setOnClickListener(v -> { if (last1>0) print("C1", last1); else toast("No number yet"); });
        findViewById(R.id.btnC1Pick).setOnClickListener(v -> showPicker(1));
        findViewById(R.id.btnC1Reset).setOnClickListener(v -> { c1=0; last1=0; save(); toast("Counter 1 reset"); });

        findViewById(R.id.btnC2Next).setOnClickListener(v -> { c2++; last2 = c2; save(); print("C2", c2); });
        findViewById(R.id.btnC2Reprint).setOnClickListener(v -> { if (last2>0) print("C2", last2); else toast("No number yet"); });
        findViewById(R.id.btnC2Pick).setOnClickListener(v -> showPicker(2));
        findViewById(R.id.btnC2Reset).setOnClickListener(v -> { c2=0; last2=0; save(); toast("Counter 2 reset"); });
    }

    private void save() {
        prefs.edit()
                .putInt("c1", c1).putInt("c2", c2)
                .putInt("last1", last1).putInt("last2", last2)
                .apply();
    }

    private void ensureBtPermissions() {
        if (adapter == null) return;
        List<String> req = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                req.add(Manifest.permission.BLUETOOTH_CONNECT);
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                req.add(Manifest.permission.BLUETOOTH_SCAN);
        } else {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                req.add(Manifest.permission.ACCESS_COARSE_LOCATION);
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                req.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (!req.isEmpty()) {
            ActivityCompat.requestPermissions(this, req.toArray(new String[0]), REQ_BT_PERMS);
        }
    }

    private void openPrinterPicker() {
        if (adapter == null) {
            toast("Bluetooth not supported");
            return;
        }
        if (!adapter.isEnabled()) {
            adapter.enable();
            toast("Turning on Bluetooth...");
        }

        // 组合「已配对 + 扫描到」列表
        scanList.clear();
        List<String> display = new ArrayList<>();

        // 已配对
        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        if (bonded != null) {
            for (BluetoothDevice d : bonded) {
                scanList.add(d);
                display.add("[Paired] " + d.getName() + " (" + d.getAddress() + ")");
            }
        }

        scanAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>(display));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Select Printer")
                .setAdapter(scanAdapter, (d, which) -> {
                    currentPrinter = scanList.get(which);
                    savedMac = currentPrinter.getAddress();
                    prefs.edit().putString("printer_mac", savedMac).apply();
                    tvPrinter.setText("Printer: " + currentPrinter.getName() + " (" + savedMac + ")");
                    toast("Selected: " + currentPrinter.getName());
                })
                .setPositiveButton("Scan", null)
                .setNegativeButton("Close", null)
                .create();

        dialog.setOnShowListener(dlg -> {
            Button scanBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            scanBtn.setOnClickListener(v -> startDiscovery());
        });

        dialog.show();
    }

    private void startDiscovery() {
        if (adapter == null) return;
        if (ActivityCompat.checkSelfPermission(this, Build.VERSION.SDK_INT >= 31 ?
                Manifest.permission.BLUETOOTH_SCAN : Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ensureBtPermissions();
            return;
        }
        if (adapter.isDiscovering()) adapter.cancelDiscovery();
        scanList.clear();
        scanAdapter.clear();

        // 先把已配对放入显示
        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        if (bonded != null) {
            for (BluetoothDevice d : bonded) {
                scanList.add(d);
                scanAdapter.add("[Paired] " + d.getName() + " (" + d.getAddress() + ")");
            }
        }

        adapter.startDiscovery();
        toast("Scanning...");
    }

    private void showPicker(int whichCounter) {
        int max = (whichCounter == 1) ? c1 : c2;
        if (max <= 0) { toast("No printed numbers yet"); return; }

        NumberPicker picker = new NumberPicker(this);
        picker.setMinValue(1);
        picker.setMaxValue(max);

        new AlertDialog.Builder(this)
                .setTitle("Select number (C" + whichCounter + ")")
                .setView(picker)
                .setPositiveButton("Print", (d,w) -> {
                    int val = picker.getValue();
                    print(whichCounter == 1 ? "C1" : "C2", val);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void print(String counter, int number) {
        if (currentPrinter == null && savedMac != null && adapter != null) {
            try { currentPrinter = adapter.getRemoteDevice(savedMac); } catch (Exception ignored) {}
        }
        if (currentPrinter == null) { toast("Please select printer first"); return; }

        String ticket =
                "====================\n" +
                new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date()) + "\n" +
                "KB Carnival Stall 29\n" +
                "Counter " + counter + "\n" +
                "KB " + number + "\n" +
                "Please wait within 15 min\n" +
                "====================\n\n";

        BluetoothSocket socket = null;
        OutputStream os = null;
        try {
            if (ActivityCompat.checkSelfPermission(this, Build.VERSION.SDK_INT >= 31 ?
                    Manifest.permission.BLUETOOTH_CONNECT : Manifest.permission.BLUETOOTH)
                    != PackageManager.PERMISSION_GRANTED) {
                ensureBtPermissions(); return;
            }
            socket = currentPrinter.createRfcommSocketToServiceRecord(
                    UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")); // SPP
            adapter.cancelDiscovery();
            socket.connect();
            os = socket.getOutputStream();

            // ESC/POS: 初始化 + 文本
            os.write(new byte[]{0x1B, 0x40}); // init
            os.write(ticket.getBytes("GBK"));
            os.flush();

            toast("Printed: " + counter + " - " + number);
        } catch (Exception e) {
            toast("Print failed: " + e.getMessage());
        } finally {
            try { if (os != null) os.close(); } catch (Exception ignored) {}
            try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    @Override protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(discoveryReceiver); } catch (Exception ignored) {}
    }

    @Override public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_BT_PERMS) {
            // 这里简单处理：再次尝试
        }
    }
}
