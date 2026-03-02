package com.example.iotgps;

import android.Manifest;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Bundle;
import android.os.Looper;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

public class MapActivity extends AppCompatActivity {

    private IndoorMapCanvas mapCanvas;
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private boolean isInitialOriginSet = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_map);

        mapCanvas = findViewById(R.id.mapCanvas);
        Button btnSetOrigin = findViewById(R.id.btnSetOrigin);

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        if (!MainActivity.recentLocations.isEmpty()) {
            Location latest = MainActivity.recentLocations.getLast();
            mapCanvas.setOriginAndCenter(latest);
            mapCanvas.setLocations(MainActivity.recentLocations);
            isInitialOriginSet = true;
        }

        btnSetOrigin.setOnClickListener(v -> {
            if (!MainActivity.recentLocations.isEmpty()) {
                mapCanvas.setOriginAndCenter(MainActivity.recentLocations.getLast());
            }
        });

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                for (Location location : locationResult.getLocations()) {
                    MainActivity.recentLocations.add(location);
                    if (MainActivity.recentLocations.size() > 15) {
                        MainActivity.recentLocations.removeFirst();
                    }

                    if (!isInitialOriginSet) {
                        mapCanvas.setOriginAndCenter(location);
                        isInitialOriginSet = true;
                    }

                    mapCanvas.setLocations(MainActivity.recentLocations);
                }
            }
        };
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            long interval = MainActivity.isTenHz ? 100 : 1000;
            LocationRequest locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, interval)
                    .setMinUpdateIntervalMillis(interval)
                    .build();
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (fusedLocationClient != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }
    }
}