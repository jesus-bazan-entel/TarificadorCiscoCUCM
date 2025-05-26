// realtime.js - Call monitoring and notifications using AJAX instead of WebSockets
(() => {
    // Configuration
    const config = {
        // How often to check for alerts (milliseconds)
        alertPollInterval: 10000,
        // How often to refresh active calls (milliseconds)
        callsRefreshInterval: 5000
    };

    // State variables
    let alertInterval = null;
    let callsInterval = null;
    let activeCalls = [];
    
    // DOM Elements (will be set on init if they exist)
    let callsTableBody = null;
    let callsCount = null;

    // Function to show toast notifications
    function showToast(title, message, type = 'info') {
        try {
            const toast = document.createElement('div');
            toast.className = 'toast show position-fixed bottom-0 end-0 m-3';
            toast.setAttribute('role', 'alert');
            toast.setAttribute('aria-live', 'assertive');
            toast.setAttribute('aria-atomic', 'true');
            
            const bgClass = type === 'info' ? 'bg-info' : type === 'warning' ? 'bg-warning' : 'bg-success';
            const textClass = type === 'warning' ? 'text-dark' : 'text-white';
            
            toast.innerHTML = `
                <div class="toast-header ${bgClass} ${textClass}">
                    <strong class="me-auto">${title}</strong>
                    <small>Ahora</small>
                    <button type="button" class="btn-close" data-bs-dismiss="toast" aria-label="Close"></button>
                </div>
                <div class="toast-body">
                    ${message}
                </div>
            `;
            document.body.appendChild(toast);
            
            // Auto-remove toast after 5 seconds
            setTimeout(() => {
                toast.remove();
            }, 5000);
        } catch (error) {
            console.error('Error showing toast:', error);
        }
    }

    // Function to check for balance alerts via AJAX
    function checkBalanceAlerts() {
        fetch('/api/balance_alerts')
            .then(response => {
                if (!response.ok) {
                    throw new Error(`HTTP error! Status: ${response.status}`);
                }
                return response.json();
            })
            .then(data => {
                // Process any balance alerts
                if (data && data.length > 0) {
                    data.forEach(alert => {
                        showToast('Alerta de Saldo', `
                            El anexo ${alert.calling_number} tiene un saldo crítico:<br>
                            $${alert.saldo.toFixed(2)}
                        `, 'warning');
                    });
                    
                    // Recargar página si estamos en saldo
                    if (window.location.pathname === '/dashboard/saldo') {
                        setTimeout(() => {
                            location.reload();
                        }, 3000);
                    }
                }
            })
            .catch(error => {
                console.error('Error checking balance alerts:', error);
            });
    }

    // Function to request active calls from the server
    function requestActiveCalls() {
        // Only proceed if we're on the active calls page
        if (!callsTableBody || !callsCount) return;
        
        fetch('/api/active_calls')
            .then(response => {
                if (!response.ok) {
                    throw new Error(`HTTP error! Status: ${response.status}`);
                }
                return response.json();
            })
            .then(data => {
                updateCallsDisplay(data);
            })
            .catch(error => {
                console.error('Error fetching active calls:', error);
            });
    }

    // Function to update the calls display with new data
    function updateCallsDisplay(calls) {
        // Only proceed if we're on the active calls page
        if (!callsTableBody || !callsCount) return;
        
        activeCalls = calls;
        
        // Update count
        callsCount.textContent = calls.length;
        
        // Clear current table
        callsTableBody.innerHTML = '';
        
        // Add each call to the table
        calls.forEach(call => {
            const row = document.createElement('tr');
            
            // Format duration as minutes:seconds
            const minutes = Math.floor(call.duration / 60);
            const seconds = call.duration % 60;
            const formattedDuration = `${minutes}:${seconds.toString().padStart(2, '0')}`;
            
            row.innerHTML = `
                <td>${call.calling_number}</td>
                <td>${call.called_number}</td>
                <td>${new Date(call.start_time).toLocaleTimeString()}</td>
                <td>${formattedDuration}</td>
            `;
            
            callsTableBody.appendChild(row);
        });
    }

    // Initialize everything
    function init() {
        try {
            console.log('Initializing realtime.js (AJAX version)');
            
            // Start checking for balance alerts (on all pages)
            alertInterval = setInterval(checkBalanceAlerts, config.alertPollInterval);
            
            // Check if we're on the active calls monitoring page
            callsTableBody = document.getElementById('active-calls-body');
            callsCount = document.getElementById('active-calls-count');
            
            if (callsTableBody && callsCount) {
                console.log('Active calls monitoring initialized');
                // If we're on the active calls page, start monitoring
                requestActiveCalls(); // Initial request
                callsInterval = setInterval(requestActiveCalls, config.callsRefreshInterval);
            }
        } catch (error) {
            console.error('Error initializing realtime.js:', error);
        }
    }

    // Clean up on page unload
    function cleanup() {
        if (alertInterval) {
            clearInterval(alertInterval);
        }
        if (callsInterval) {
            clearInterval(callsInterval);
        }
    }

    // Initialize when the document is ready
    document.addEventListener('DOMContentLoaded', init);
    window.addEventListener('beforeunload', cleanup);
    
    // Expose functions for debugging
    window.realtime = {
        requestActiveCalls,
        updateCallsDisplay,
        showToast,
        checkBalanceAlerts
    };
})();