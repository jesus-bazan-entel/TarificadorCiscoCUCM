// realtime-basic.js - Versión básica para todas las páginas excepto monitoreo
(() => {
    // Configuración básica de WebSocket
    try {
        const websocket = new WebSocket(`ws://${window.location.host}/ws`);
        
        websocket.onopen = function(event) {
            console.log('WebSocket connection established (basic mode)');
        };
        
        websocket.onmessage = function(event) {
            try {
                const data = JSON.parse(event.data);
                
                // Solo procesar alertas importantes
                if (data.type === 'balance_alert') {
                    // Mostrar alerta de saldo
                    showToast('Alerta de Saldo', `
                        El anexo ${data.data.calling_number} tiene un saldo crítico:<br>
                        $${data.data.saldo.toFixed(2)}
                    `, 'warning');
                    
                    // Recargar página si estamos en saldo
                    if (window.location.pathname === '/dashboard/saldo') {
                        setTimeout(() => {
                            location.reload();
                        }, 3000);
                    }
                }
            } catch (error) {
                console.error('Error processing WebSocket message:', error);
            }
        };
        
        websocket.onclose = function(event) {
            console.log('WebSocket connection closed (basic mode)');
        };
        
        // Mostrar notificaciones toast
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
    } catch (error) {
        console.error('Error initializing WebSocket (basic mode):', error);
    }
})();