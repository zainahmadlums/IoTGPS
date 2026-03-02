package com.example.iotgps;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Bundle;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;

    private TextView tvLatitude;
    private TextView tvLongitude;
    private TextView tvAccuracy;
    private TextView tvTimeSinceUpdate;
    private TextView tvOffset;
    private Button btnViewLogs;
    private Button btnViewMap;
    private Button btnToggleFreq;

    private Location lastLocation = null;
    public static ArrayList<String> logsList = new ArrayList<>();
    public static LinkedList<Location> recentLocations = new LinkedList<>();

    public static boolean isTenHz = false;

    private final ActivityResultLauncher<String[]> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                Boolean fineLocationGranted = result.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false);
                if (fineLocationGranted != null && fineLocationGranted) {
                    startLocationUpdates();
                } else {
                    Toast.makeText(this, "Location permission is required.", Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvLatitude = findViewById(R.id.tvLatitude);
        tvLongitude = findViewById(R.id.tvLongitude);
        tvAccuracy = findViewById(R.id.tvAccuracy);
        tvTimeSinceUpdate = findViewById(R.id.tvTimeSinceUpdate);
        tvOffset = findViewById(R.id.tvOffset);
        btnViewLogs = findViewById(R.id.btnViewLogs);
        btnViewMap = findViewById(R.id.btnViewMap);
        btnToggleFreq = findViewById(R.id.btnToggleFreq);

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        btnViewLogs.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, LogsActivity.class);
            startActivity(intent);
        });

        btnViewMap.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, MapActivity.class);
            startActivity(intent);
        });

        btnToggleFreq.setOnClickListener(v -> toggleFrequency());

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                for (Location location : locationResult.getLocations()) {
                    updateLocationData(location);
                }
            }
        };
    }

    private void toggleFrequency() {
        isTenHz = !isTenHz;
        btnToggleFreq.setText(isTenHz ? "Switch to 1Hz (1000ms)" : "Switch to 10Hz (100ms)");

        if (fusedLocationClient != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }

        if (checkPermissions()) {
            startLocationUpdates();
        }
    }

    private void updateLocationData(Location location) {
        long timeSinceLastUpdate = 0;
        float totalDist = 0;
        float latDist = 0;
        float lonDist = 0;

        if (lastLocation != null) {
            timeSinceLastUpdate = location.getTime() - lastLocation.getTime();
            float[] results = new float[1];
            Location.distanceBetween(lastLocation.getLatitude(), lastLocation.getLongitude(), location.getLatitude(), location.getLongitude(), results);
            totalDist = results[0];
            Location.distanceBetween(lastLocation.getLatitude(), lastLocation.getLongitude(), location.getLatitude(), lastLocation.getLongitude(), results);
            latDist = results[0];
            Location.distanceBetween(lastLocation.getLatitude(), lastLocation.getLongitude(), lastLocation.getLatitude(), location.getLongitude(), results);
            lonDist = results[0];
        }

        recentLocations.add(location);
        if (recentLocations.size() > 15) {
            recentLocations.removeFirst();
        }

        double sumLat = 0, sumLon = 0;
        for (Location loc : recentLocations) {
            sumLat += loc.getLatitude();
            sumLon += loc.getLongitude();
        }
        double avgLat = sumLat / recentLocations.size();
        double avgLon = sumLon / recentLocations.size();

        float[] offsetResults = new float[1];
        Location.distanceBetween(avgLat, avgLon, location.getLatitude(), avgLon, offsetResults);
        float offsetY = offsetResults[0] * (location.getLatitude() > avgLat ? 1 : -1);
        Location.distanceBetween(avgLat, avgLon, avgLat, location.getLongitude(), offsetResults);
        float offsetX = offsetResults[0] * (location.getLongitude() > avgLon ? 1 : -1);

        tvLatitude.setText("Latitude: " + location.getLatitude());
        tvLongitude.setText("Longitude: " + location.getLongitude());
        tvAccuracy.setText("Error Radius: " + location.getAccuracy() + " meters");
        tvTimeSinceUpdate.setText("Time since last update: " + timeSinceLastUpdate + " ms");
        tvOffset.setText(String.format(Locale.US, "Offset from Origin: X: %.2fm, Y: %.2fm", offsetX, offsetY));

        String logEntry = String.format(Locale.US,
                "Lat: %.6f\nLon: %.6f\nError: %.1fm\nTime Diff: %dms\nLat Dist: %.2fm\nLon Dist: %.2fm\nTotal Dist: %.2fm\nOrigin Offset: X: %.2fm, Y: %.2fm\n-----------------------",
                location.getLatitude(), location.getLongitude(), location.getAccuracy(),
                timeSinceLastUpdate, latDist, lonDist, totalDist, offsetX, offsetY);

        logsList.add(0, logEntry);
        if (logsList.size() > 60) {
            logsList.remove(logsList.size() - 1);
        }

        lastLocation = location;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (checkPermissions()) {
            startLocationUpdates();
        } else {
            requestPermissionLauncher.launch(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION});
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (fusedLocationClient != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }
    }

    private boolean checkPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void startLocationUpdates() {
        if (!checkPermissions()) return;

        long interval = isTenHz ? 100 : 1000;
        LocationRequest locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, interval)
                .setMinUpdateIntervalMillis(interval)
                .build();

        try {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
        } catch (SecurityException e) {
            e.printStackTrace();
        }
    }
}