package com.tarificador;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CallManager {
    // Singleton instance
    private static CallManager instance;
    
    // Map to store active calls: key is calling+called number, value is start time
    private final Map<String, Instant> activeCalls = new ConcurrentHashMap<>();
    
    private CallManager() {}
    
    public static synchronized CallManager getInstance() {
        if (instance == null) {
            instance = new CallManager();
        }
        return instance;
    }
    
    public void addCall(String callingNumber, String calledNumber) {
        String callId = callingNumber + "-" + calledNumber;
        activeCalls.put(callId, Instant.now());
        System.out.println("Llamada agregada: " + callId);
    }
    
    public Instant getCallStartTime(String callingNumber, String calledNumber) {
        String callId = callingNumber + "-" + calledNumber;
        return activeCalls.getOrDefault(callId, Instant.now());
    }
    
    public void removeCall(String callingNumber, String calledNumber) {
        String callId = callingNumber + "-" + calledNumber;
        activeCalls.remove(callId);
        System.out.println("Llamada eliminada: " + callId);
    }
    
    public boolean hasActiveCall(String callingNumber, String calledNumber) {
        String callId = callingNumber + "-" + calledNumber;
        return activeCalls.containsKey(callId);
    }
    
    public int getActiveCallCount() {
        return activeCalls.size();
    }
    
    public Map<String, String> getActiveCallsInfo() {
        Map<String, String> callsInfo = new HashMap<>();
        for (Map.Entry<String, Instant> entry : activeCalls.entrySet()) {
            String callId = entry.getKey();
            Instant startTime = entry.getValue();
            long durationSeconds = Instant.now().getEpochSecond() - startTime.getEpochSecond();
            callsInfo.put(callId, formatDuration(durationSeconds));
        }
        return callsInfo;
    }
    
    private String formatDuration(long seconds) {
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        return String.format("%02d:%02d:%02d", hours, minutes, secs);
    }
}
