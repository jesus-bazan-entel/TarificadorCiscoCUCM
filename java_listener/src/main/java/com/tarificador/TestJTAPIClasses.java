package com.tarificador;

import javax.telephony.*;
import javax.telephony.events.*;
import com.cisco.jtapi.extensions.*;

public class TestJTAPIClasses {
    public static void main(String[] args) {
        System.out.println("=== Verificando clases JTAPI disponibles ===");
        
        // Verificar eventos básicos
        try {
            System.out.println("ConnCreatedEv: " + ConnCreatedEv.class.getName());
        } catch (Exception e) {
            System.out.println("ConnCreatedEv NO disponible");
        }
        
        try {
            System.out.println("ConnConnectedEv: " + ConnConnectedEv.class.getName());
        } catch (Exception e) {
            System.out.println("ConnConnectedEv NO disponible");
        }
        
        try {
            System.out.println("ConnDisconnectedEv: " + ConnDisconnectedEv.class.getName());
        } catch (Exception e) {
            System.out.println("ConnDisconnectedEv NO disponible");
        }
        
        // Verificar interfaces
        try {
            System.out.println("CallObserver: " + CallObserver.class.getName());
        } catch (Exception e) {
            System.out.println("CallObserver NO disponible");
        }
        
        // Verificar clases Cisco
        try {
            System.out.println("CiscoProvider: " + CiscoProvider.class.getName());
        } catch (Exception e) {
            System.out.println("CiscoProvider NO disponible");
        }
        
        try {
            System.out.println("CiscoCall: " + CiscoCall.class.getName());
        } catch (Exception e) {
            System.out.println("CiscoCall NO disponible");
        }
        
        System.out.println("\n=== Fin de verificación ===");
    }
}