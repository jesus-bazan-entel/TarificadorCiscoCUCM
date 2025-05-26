package com.tarificador;

import javax.telephony.*;
import javax.telephony.events.*;
import javax.telephony.callcontrol.*;
import javax.telephony.callcontrol.events.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.time.Duration;
import com.fasterxml.jackson.databind.DeserializationFeature;

public class CallListener implements CallControlCallObserver {
    private static final HttpClient client = HttpClient.newHttpClient();
    private static final ObjectMapper objectMapper = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);    
    //private static final ObjectMapper objectMapper = new ObjectMapper();
    
    // Almacenar información de las llamadas activas
    private static final Map<String, CallData> activeCalls = new ConcurrentHashMap<>();
    
    private static class CallData {
        String callId;
        String callingNumber;
        String calledNumber;
        Instant startTime;
        Instant dialingTime; // Cuando empieza a marcar
        Instant networkReachedTime; // Cuando llega a la red del destino
        Instant networkAlertingTime; // Cuando empieza a timbrar en destino
        Instant destinationEstablishedTime; // Cuando el destino contesta
        Instant endTime;
        String status;
        String direction = "unknown";
        int releaseCause = 0;
        boolean hasDialedNumber = false;

        // Nuevo campo para la tarea de reporte periódico
        ScheduledFuture<?> reporterTask;

        // Modificar el constructor para incluir el callId
        CallData(String callId, String callingNumber, String calledNumber) {
            this.callId = callId;  // Asignar el callId desde el constructor
            this.callingNumber = callingNumber;
            this.calledNumber = calledNumber;
            this.startTime = Instant.now();
            this.status = "initiated";
        }

    }

    @Override
    public void callChangedEvent(CallEv[] events) {
        for (CallEv ev : events) {
            Instant eventTime = Instant.now();
            System.out.println(String.format("[%s] Evento: %s ID: %d", 
                eventTime, ev.getClass().getSimpleName(), ev.getID()));
            
            try {
                Call call = ev.getCall();
                String callId = String.valueOf(call.hashCode());
                
                // Debug para eventos de conexión
                if (ev instanceof ConnEv) {
                    ConnEv connEv = (ConnEv) ev;
                    Connection conn = connEv.getConnection();
                    System.out.println("  Conexión: " + conn.getAddress().getName() + 
                                     " Estado: " + getConnectionState(conn));
                }
                
                // Procesar eventos básicos
                if (ev instanceof ConnCreatedEv) {
                    handleConnCreated((ConnCreatedEv) ev, callId);
                } else if (ev instanceof ConnInProgressEv) {
                    handleConnInProgress((ConnInProgressEv) ev, callId);
                } else if (ev instanceof ConnAlertingEv) {
                    handleConnAlerting((ConnAlertingEv) ev, callId);
                } else if (ev instanceof ConnConnectedEv) {
                    handleConnConnected((ConnConnectedEv) ev, callId);
                } else if (ev instanceof ConnDisconnectedEv) {
                    handleConnDisconnected((ConnDisconnectedEv) ev, callId);
                } else if (ev instanceof ConnFailedEv) {
                    handleConnFailed((ConnFailedEv) ev, callId);
                }
                
                // Procesar eventos CallControl
                if (ev instanceof CallCtlConnDialingEv) {
                    handleCallCtlConnDialing((CallCtlConnDialingEv) ev, callId);
                } else if (ev instanceof CallCtlConnNetworkReachedEv) {
                    handleCallCtlConnNetworkReached((CallCtlConnNetworkReachedEv) ev, callId);
                } else if (ev instanceof CallCtlConnNetworkAlertingEv) {
                    handleCallCtlConnNetworkAlerting((CallCtlConnNetworkAlertingEv) ev, callId);
                } else if (ev instanceof CallCtlConnEstablishedEv) {
                    handleCallCtlConnEstablished((CallCtlConnEstablishedEv) ev, callId);
                } else if (ev instanceof CallCtlTermConnRingingEv) {
                    handleCallCtlTermConnRinging((CallCtlTermConnRingingEv) ev, callId);
                } else if (ev instanceof CallCtlTermConnTalkingEv) {
                    handleCallCtlTermConnTalking((CallCtlTermConnTalkingEv) ev, callId);
                } else if (ev instanceof CallCtlConnDisconnectedEv) {
                    handleCallCtlConnDisconnected((CallCtlConnDisconnectedEv) ev, callId);
                }
                
                // Otros eventos
                if (ev instanceof CallActiveEv) {
                    System.out.println("Llamada activa: " + callId);
                } else if (ev instanceof CallInvalidEv) {
                    handleCallInvalid((CallInvalidEv) ev, callId);
                }
                
            } catch (Exception e) {
                System.err.println("Error procesando evento: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }
    
    private String getConnectionState(Connection conn) {
        if (conn == null) return "null";
        
        if (conn instanceof CallControlConnection) {
            CallControlConnection ccConn = (CallControlConnection) conn;
            try {
                switch (ccConn.getCallControlState()) {
                    case CallControlConnection.IDLE: return "IDLE";
                    case CallControlConnection.INPROGRESS: return "INPROGRESS";
                    case CallControlConnection.ALERTING: return "ALERTING";
                    case CallControlConnection.DIALING: return "DIALING";
                    case CallControlConnection.ESTABLISHED: return "ESTABLISHED";
                    case CallControlConnection.FAILED: return "FAILED";
                    case CallControlConnection.DISCONNECTED: return "DISCONNECTED";
                    default: return "UNKNOWN_STATE";
                }
            } catch (Exception e) {
                return "ERROR";
            }
        }
        
        return "CONN_" + conn.getState();
    }
    
    private void handleConnCreated(ConnCreatedEv ev, String callId) {
        try {
            CallData existingCall = activeCalls.get(callId);
            if (existingCall != null) {
                Connection conn = ev.getConnection();
                String newAddress = conn.getAddress().getName();
                
                if (!newAddress.equals(existingCall.callingNumber)) {
                    existingCall.calledNumber = newAddress;
                    existingCall.hasDialedNumber = true;
                    existingCall.direction = "outbound";
                    System.out.println("  Actualizado número destino: " + newAddress);

                    // Verificando si se tiene saldo disponible para el destino marcado
                    if (!canMakeCall(existingCall.callingNumber, existingCall.calledNumber)) {
                        System.out.println("*** SALDO INSUFICIENTE PARA DESTINO ***");
                        System.out.println("  " + existingCall.callingNumber + " no puede llamar a " + existingCall.calledNumber);
                        try {
                            // Desconectar la llamada
                            CallControlCall ccCall = (CallControlCall) ev.getCall();
                            ccCall.drop();
                            System.out.println("  Llamada terminada por saldo insuficiente");
                        } catch (Exception e) {
                            System.err.println("Error terminando llamada por saldo insuficiente: " + e.getMessage());
                        }
                        return;
                    }

                }
                return;
            }
            
            Connection conn = ev.getConnection();
            Address address = conn.getAddress();
            String addressName = address.getName();
            
            CallData callData = new CallData(callId, addressName, addressName);
            activeCalls.put(callId, callData);
            
            System.out.println("  Llamada creada - ID: " + callId + 
                             ", De: " + callData.callingNumber);
            
        } catch (Exception e) {
            System.err.println("Error en handleConnCreated: " + e.getMessage());
        }
    }
    
    private void handleCallCtlConnDialing(CallCtlConnDialingEv ev, String callId) {
        CallData callData = activeCalls.get(callId);
        if (callData != null) {
            callData.dialingTime = Instant.now();
            callData.status = "dialing";
            System.out.println("  MARCANDO: " + callId);

            if (!hasSufficientBalance(callData.callingNumber)) {
                System.out.println("Saldo insuficiente para " + callData.callingNumber + ". Terminando llamada...");
                try {
                    CallControlCall ccCall = (CallControlCall) ev.getCall();
                    ccCall.drop();
                } catch (Exception e) {
                    System.err.println("Error terminando llamada por saldo insuficiente: " + e.getMessage());
                }
                return;
            }            
        }
    }
    
    private void handleCallCtlConnDisconnected(CallCtlConnDisconnectedEv ev, String callId) {
        CallData callData = activeCalls.get(callId);
        if (callData != null) {
            //callData.networkReachedTime = Instant.now();
            System.out.println("  Desconexión: " + Instant.now());
        }
    }

    private void handleCallCtlConnNetworkReached(CallCtlConnNetworkReachedEv ev, String callId) {
        CallData callData = activeCalls.get(callId);
        if (callData != null) {
            callData.networkReachedTime = Instant.now();
            System.out.println("  RED ALCANZADA: " + callId);
        }
    }
    
    private void handleCallCtlConnNetworkAlerting(CallCtlConnNetworkAlertingEv ev, String callId) {
        CallData callData = activeCalls.get(callId);
        if (callData != null) {
            // Este evento indica que está timbrando en el destino
            callData.networkAlertingTime = Instant.now();
            callData.status = "ringing";
            System.out.println("  TIMBRANDO EN DESTINO: " + callId);
        }
    }
    
    private void handleCallCtlTermConnRinging(CallCtlTermConnRingingEv ev, String callId) {
        CallData callData = activeCalls.get(callId);
        if (callData != null) {
            System.out.println("  TERMINAL RINGING: " + callId);
        }
    }
    
    private void handleCallCtlConnEstablished(CallCtlConnEstablishedEv ev, String callId) {
        CallData callData = activeCalls.get(callId);
        if (callData != null) {
            try {
                Connection conn = ev.getConnection();
                String address = conn.getAddress().getName();
                
                // Si el evento es para el destino y no el origen
                if (!address.equals(callData.callingNumber) && address.equals(callData.calledNumber)) {
                    callData.destinationEstablishedTime = Instant.now();
                    callData.status = "established";
                    System.out.println("  *** DESTINO CONTESTÓ (ESTABLISHED) ***: " + callId + 
                                     " en " + callData.destinationEstablishedTime);

                    // *** REPORTAR LLAMADA ACTIVA AL SISTEMA ***
                    reportActiveCall(callData);

                    // Versión más explícita de la asignación
                    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
                    ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(() -> {
                        reportActiveCall(callData);
                    }, 10, 5, TimeUnit.SECONDS);
                    
                    // Asignación explícita
                    callData.reporterTask = task;
                    
                    // Programar reportes periódicos para actualizar duración/costo en tiempo real
                    if (callData.reporterTask != null) {
                        System.out.println("Reporting task scheduled successfully for call: " + callId);
                    }
                                                        
                } else {
                    System.out.println("  CONEXIÓN ESTABLECIDA (origen): " + callId);
                }
            } catch (Exception e) {
                System.err.println("Error en handleCallCtlConnEstablished: " + e.getMessage());
            }
        }
    }
    
    private void handleCallCtlTermConnTalking(CallCtlTermConnTalkingEv ev, String callId) {
        CallData callData = activeCalls.get(callId);
        if (callData != null) {
            try {
                // Verificar si es el evento talking del destino, no del origen
                TerminalConnection termConn = ev.getTerminalConnection();
                String terminalName = termConn.getTerminal().getName();
                
                System.out.println("  TALKING detectado en terminal: " + terminalName);
                
                // Solo registrar si es diferente del origen
                if (!terminalName.equals(callData.callingNumber)) {
                    System.out.println("  *** DESTINO TALKING ***: " + callId);
                }
            } catch (Exception e) {
                System.err.println("Error en handleCallCtlTermConnTalking: " + e.getMessage());
            }
        }
    }
    
    private void handleConnInProgress(ConnInProgressEv ev, String callId) {
        CallData callData = activeCalls.get(callId);
        if (callData != null) {
            callData.status = "in_progress";
            System.out.println("  Llamada en progreso: " + callId);
        }
    }
    
    private void handleConnAlerting(ConnAlertingEv ev, String callId) {
        CallData callData = activeCalls.get(callId);
        if (callData != null) {
            callData.status = "alerting";
            System.out.println("  Alerting: " + callId);
        }
    }
    
    private void handleConnConnected(ConnConnectedEv ev, String callId) {
        CallData callData = activeCalls.get(callId);
        if (callData != null) {
            try {
                String addressName = ev.getConnection().getAddress().getName();
                if (!addressName.equals(callData.callingNumber)) {
                    callData.calledNumber = addressName;
                    callData.hasDialedNumber = true;
                }
            } catch (Exception e) {
                // Ignorar errores
            }
            
            System.out.println("  Llamada conectada - ID: " + callId + 
                             ", De: " + callData.callingNumber + 
                             ", A: " + callData.calledNumber);
        }
    }
    
    private void handleConnDisconnected(ConnDisconnectedEv ev, String callId) {
        CallData callData = activeCalls.get(callId);
        if (callData != null) {
            callData.endTime = Instant.now();
            callData.status = "disconnected";
            
            // Obtener causa de desconexión si es posible
            if (ev instanceof CallCtlConnDisconnectedEv) {
                CallCtlConnDisconnectedEv ctlEv = (CallCtlConnDisconnectedEv) ev;
                callData.releaseCause = ctlEv.getCallControlCause();
            }
            
            System.out.println("  Llamada desconectada - ID: " + callId);

            if (callData.reporterTask != null) {
                callData.reporterTask.cancel(false);
                System.out.println("  Tarea de reporte periódico cancelada para llamada: " + callId);
            }

            // Mostrar resumen de tiempos
            System.out.println("\n=== RESUMEN DE TIEMPOS ===");
            System.out.println("  Start: " + callData.startTime);
            System.out.println("  Dialing: " + callData.dialingTime);
            System.out.println("  Network Reached: " + callData.networkReachedTime);
            System.out.println("  Network Alerting (Ringing): " + callData.networkAlertingTime);
            System.out.println("  Destination Established (Answered): " + callData.destinationEstablishedTime);
            System.out.println("  End: " + callData.endTime);
            
            // Calcular duraciones
            if (callData.destinationEstablishedTime != null && callData.endTime != null) {
                long billableDuration = callData.endTime.getEpochSecond() - 
                                      callData.destinationEstablishedTime.getEpochSecond();
                System.out.println("  DURACIÓN FACTURABLE (desde established): " + billableDuration + " segundos");
            }
            
            if (callData.networkAlertingTime != null && callData.destinationEstablishedTime != null) {
                long ringDuration = callData.destinationEstablishedTime.getEpochSecond() - 
                                   callData.networkAlertingTime.getEpochSecond();
                System.out.println("  Tiempo de timbre: " + ringDuration + " segundos");
            }
            System.out.println("========================\n");
            
            boolean shouldSendCDR = false;
            
            if (callData.hasDialedNumber || !callData.callingNumber.equals(callData.calledNumber)) {
                shouldSendCDR = true;
            }
            
            if (callData.callingNumber.equals(callData.calledNumber) && !callData.hasDialedNumber) {
                System.out.println("Ignorando llamada: solo se levantó y colgó el auricular");
                shouldSendCDR = false;
            }
            
            if (shouldSendCDR) {
                sendCDR(callData);
            }

            // GUARDAR EL CALLID ANTES DE ENVIARLO
            String idToDelete = callData.callId; // Usar el ID guardado en el objeto CallData

            // Reportar eliminación de llamada activa
            try {
                // Asegurarse de usar el mismo ID que se usó al registrar la llamada
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:8000/api/active-calls/" + callId))
                        .DELETE()
                        .build();
                
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    System.out.println("Llamada activa eliminada del monitoreo: " + callId);
                } else {
                    System.err.println("Error eliminando llamada activa: " + response.statusCode() + " - " + response.body());
                }
            } catch (Exception e) {
                System.err.println("Error reportando fin de llamada: " + e.getMessage());
            }

            activeCalls.remove(callId);

        }
    }
    
    private void handleConnFailed(ConnFailedEv ev, String callId) {
        CallData callData = activeCalls.get(callId);
        if (callData != null) {
            callData.endTime = Instant.now();
            callData.status = "failed";
            
            if (ev instanceof CallCtlConnFailedEv) {
                CallCtlConnFailedEv ctlEv = (CallCtlConnFailedEv) ev;
                callData.releaseCause = ctlEv.getCallControlCause();
            }
            
            System.out.println("  Llamada fallida - ID: " + callId);

            if (callData.reporterTask != null) {
                callData.reporterTask.cancel(false);
                System.out.println("  Tarea de reporte periódico cancelada para llamada fallida: " + callId);
            }

            if (callData.hasDialedNumber || !callData.callingNumber.equals(callData.calledNumber)) {
                sendCDR(callData);
            }
            
            activeCalls.remove(callId);
        }
    }
    
    private void handleCallInvalid(CallInvalidEv ev, String callId) {
        activeCalls.remove(callId);
    }
    
    private void sendCDR(CallData callData) {
        try {
            Map<String, Object> cdr = new HashMap<>();
            cdr.put("calling_number", callData.callingNumber);
            cdr.put("called_number", callData.calledNumber);
            cdr.put("start_time", callData.startTime.toString());
            cdr.put("dialing_time", callData.dialingTime != null ? callData.dialingTime.toString() : null);
            cdr.put("network_reached_time", callData.networkReachedTime != null ? 
                    callData.networkReachedTime.toString() : null);
            cdr.put("network_alerting_time", callData.networkAlertingTime != null ? 
                    callData.networkAlertingTime.toString() : null);
            cdr.put("answer_time", callData.destinationEstablishedTime != null ? 
                    callData.destinationEstablishedTime.toString() : null);
            cdr.put("end_time", callData.endTime.toString());
            cdr.put("status", callData.status);
            cdr.put("direction", callData.direction);
            cdr.put("release_cause", callData.releaseCause);
            
            // Calcular duración total
            long durationTotal = 0;
            if (callData.startTime != null && callData.endTime != null) {
                durationTotal = callData.endTime.getEpochSecond() - callData.startTime.getEpochSecond();
            }
            cdr.put("duration_seconds", durationTotal);
            
            // Calcular duración facturable (desde que el destino contestó)
            long durationBillable = 0;
            if (callData.destinationEstablishedTime != null && callData.endTime != null) {
                durationBillable = callData.endTime.getEpochSecond() - 
                                  callData.destinationEstablishedTime.getEpochSecond();
            }
            cdr.put("duration_billable", durationBillable);
            
            String json = objectMapper.writeValueAsString(cdr);
            System.out.println("Enviando CDR: " + json);
            
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:8000/cdr"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            System.out.println("CDR enviado. Respuesta: " + response.statusCode());
            
        } catch (Exception e) {
            System.err.println("Error enviando CDR: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private boolean hasSufficientBalance(String callingNumber) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:8000/check_balance/" + callingNumber))
                    .timeout(Duration.ofSeconds(5)) 
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            
            return response.body().contains("true");
        } catch (Exception e) {
            System.err.println("Error verificando saldo: " + e.getMessage());
            return false;
        }
    }

    private static class BalanceCheckResponse {
        public boolean can_call;
        public String reason;
        public double balance;
        public String zona;
        public double tarifa_segundo;  // Cambio: tarifa por segundo, no por minuto
        public int tiempo_disponible_segundos;
        
        // Constructor vacío necesario para Jackson
        public BalanceCheckResponse() {}
    }

    // Modificar el método canMakeCall():
    private boolean canMakeCall(String callingNumber, String calledNumber) {
        try {
            String url = String.format("http://localhost:8000/check_balance_for_call/%s/%s", 
                                    callingNumber, calledNumber);
            
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            
            // *** CAMBIO: Deserializar a clase específica ***
            BalanceCheckResponse result = objectMapper.readValue(response.body(), BalanceCheckResponse.class);
            
            // Log detallado basado en segundos
            if (!result.can_call) {
                System.out.printf("  ❌ NO PUEDE LLAMAR - Saldo: $%.5f, Zona: %s, Tarifa/seg: $%.5f, Razón: %s%n",
                    result.balance,
                    result.zona != null ? result.zona : "N/A",
                    result.tarifa_segundo,
                    result.reason != null ? result.reason : "Unknown"
                );
            } else {
                // Convertir segundos a minutos:segundos para mostrar más claro
                int minutos = result.tiempo_disponible_segundos / 60;
                int segundos = result.tiempo_disponible_segundos % 60;
                System.out.printf("  ✅ PUEDE LLAMAR - Saldo: $%.5f, Zona: %s, Tarifa/seg: $%.5f, Tiempo disponible: %d:%02d%n",
                    result.balance,
                    result.zona != null ? result.zona : "N/A",
                    result.tarifa_segundo,
                    minutos, segundos
                );
            }
            
            return result.can_call;
        } catch (Exception e) {
            System.err.println("Error verificando capacidad de llamada: " + e.getMessage());
            return false;
        }
    }


    // En CallListener.java - añadir método para reportar llamadas activas
    private void reportActiveCall(CallData callData) {
        try {
            Map<String, Object> activeCall = new HashMap<>();
            activeCall.put("call_id", callData.callId);  // CAMBIAR: callId -> call_id
            activeCall.put("calling_number", callData.callingNumber);  // CAMBIAR: origin -> calling_number
            activeCall.put("called_number", callData.calledNumber);  // CAMBIAR: destination -> called_number
            activeCall.put("start_time", callData.startTime.toString());

            // Calcular duración en tiempo real
            //long durationSeconds = Instant.now().getEpochSecond() - 
            //                    (callData.destinationEstablishedTime != null ? 
            //                    callData.destinationEstablishedTime.getEpochSecond() : 
            //                    callData.startTime.getEpochSecond());
            //activeCall.put("duration", durationSeconds);
            long durationSeconds = 0;
            if (callData.destinationEstablishedTime != null) {
                durationSeconds = Instant.now().getEpochSecond() - 
                                callData.destinationEstablishedTime.getEpochSecond();
            }
            activeCall.put("current_duration", durationSeconds);  // CAMBIAR: duration -> current_duration

            // Usar cache para evitar consultas repetidas para la misma zona/destino
            double tarifaSegundo = 0.0;
            String zona = "Desconocida";
            
            // Obtener tarifa del API
            try {
                String url = String.format("http://localhost:8000/check_balance_for_call/%s/%s", 
                                        callData.callingNumber, callData.calledNumber);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(2))
                        .GET()
                        .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                BalanceCheckResponse result = objectMapper.readValue(response.body(), BalanceCheckResponse.class);
                
                tarifaSegundo = result.tarifa_segundo;
                zona = result.zona;
            } catch (Exception e) {
                System.err.println("Error obteniendo tarifa: " + e.getMessage());
            }
            
            double costoEstimado = durationSeconds * tarifaSegundo;
            activeCall.put("current_cost", costoEstimado);  // CAMBIAR: estimatedCost -> current_cost
            activeCall.put("zone", zona);
            
            // Agregar connectionId para poder terminar la llamada
            activeCall.put("connection_id", callData.callId);
            
            System.out.println("Enviando reporte de llamada activa: " + callData.callingNumber + 
                            " -> " + callData.calledNumber + 
                            " (dur: " + durationSeconds + "s, costo: $" + 
                            String.format("%.2f", costoEstimado) + ")");
            
            // Enviar al servidor
            String json = objectMapper.writeValueAsString(activeCall);
            HttpRequest reportRequest = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:8000/api/active-calls"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
                    
            HttpResponse<String> reportResponse = client.send(reportRequest, HttpResponse.BodyHandlers.ofString());
            
            if (reportResponse.statusCode() >= 400) {
                System.err.println("Error reportando llamada activa: " + reportResponse.statusCode() + 
                                " - " + reportResponse.body());
            }
        
        } catch (Exception e) {
            System.err.println("Error reporting active call: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Método auxiliar para obtener saldo
    private double obtenerSaldoActual(String anexo) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:8000/check_balance/" + anexo))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
                    
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
            
            if (result.containsKey("balance")) {
                return (Double) result.get("balance");
            }
        } catch (Exception e) {
            System.err.println("Error obteniendo saldo: " + e.getMessage());
        }
        
        return 0.0; // Valor predeterminado si hay error
    }

    // Caches para evitar llamadas HTTP repetidas
    private static final Map<String, Double> tarifaCache = new ConcurrentHashMap<>();
    private static final Map<String, String> zonaCache = new ConcurrentHashMap<>();
    private static final Map<String, Long> tarifaCacheTime = new ConcurrentHashMap<>();

}