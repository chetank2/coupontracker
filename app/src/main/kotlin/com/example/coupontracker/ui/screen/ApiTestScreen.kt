package com.example.coupontracker.ui.screen

import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.coupontracker.util.ApiTester
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiTestScreen(
    navController: NavController
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val sharedPreferences = remember { 
        context.getSharedPreferences("coupon_tracker_prefs", Context.MODE_PRIVATE)
    }
    
    // States
    var apiKey by remember { mutableStateOf(getSavedApiKey(sharedPreferences)) }
    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var isPasswordVisible by remember { mutableStateOf(false) }
    
    val apiTester = remember { ApiTester(context) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("API Connection Test") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Mistral API Connection Test",
                        style = MaterialTheme.typography.titleMedium
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("API Key") },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (isPasswordVisible) VisualTransformation.None 
                                             else PasswordVisualTransformation(),
                        trailingIcon = {
                            Button(
                                onClick = { isPasswordVisible = !isPasswordVisible },
                                modifier = Modifier.padding(4.dp)
                            ) {
                                Text(if (isPasswordVisible) "Hide" else "Show")
                            }
                        }
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // Format validation
                    if (apiKey.isNotBlank()) {
                        val isFormatValid = apiTester.isApiKeyFormatValid(apiKey)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(8.dp)
                        ) {
                            Icon(
                                imageVector = if (isFormatValid) Icons.Default.Check else Icons.Default.Warning,
                                contentDescription = null,
                                tint = if (isFormatValid) Color.Green else Color.Red
                            )
                            Text(
                                text = if (isFormatValid) "Key format is valid" else "Key format is invalid",
                                color = if (isFormatValid) Color.Green else Color.Red,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Button(
                            onClick = { saveApiKey(context, sharedPreferences, apiKey) },
                            enabled = apiKey.isNotBlank()
                        ) {
                            Text("Save Key")
                        }
                        
                        Button(
                            onClick = {
                                isTesting = true
                                testResult = null
                                coroutineScope.launch {
                                    val result = apiTester.testMistralApi(apiKey)
                                    testResult = result
                                    isTesting = false
                                }
                            },
                            enabled = apiKey.isNotBlank() && !isTesting
                        ) {
                            Text("Test Connection")
                        }
                    }
                }
            }
            
            // Test Result Card
            if (isTesting || testResult != null) {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Test Result",
                            style = MaterialTheme.typography.titleMedium
                        )
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        if (isTesting) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Testing connection...")
                        } else if (testResult != null) {
                            Icon(
                                imageVector = if (testResult!!.first) Icons.Default.Check else Icons.Default.Warning,
                                contentDescription = null,
                                tint = if (testResult!!.first) Color.Green else Color.Red,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            
                            Text(
                                text = if (testResult!!.first) "Connection Successful" else "Connection Failed",
                                color = if (testResult!!.first) Color.Green else Color.Red,
                                style = MaterialTheme.typography.titleSmall
                            )
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            Divider()
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            Text(
                                text = testResult!!.second,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun getSavedApiKey(sharedPreferences: SharedPreferences): String {
    return sharedPreferences.getString("mistral_api_key", "") ?: ""
}

private fun saveApiKey(
    context: Context,
    sharedPreferences: SharedPreferences,
    apiKey: String
) {
    sharedPreferences.edit().putString("mistral_api_key", apiKey).apply()
    Toast.makeText(context, "API key saved", Toast.LENGTH_SHORT).show()
} 