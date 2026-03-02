package com.example.iotgps;

import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import androidx.appcompat.app.AppCompatActivity;

public class LogsActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_logs);

        ListView listViewLogs = findViewById(R.id.listViewLogs);
        if (MainActivity.logsList.isEmpty()) {
            MainActivity.logsList.add("No logs available.");
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, MainActivity.logsList);
        listViewLogs.setAdapter(adapter);
    }
}