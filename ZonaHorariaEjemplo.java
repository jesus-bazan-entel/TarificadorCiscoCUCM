import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public class ZonaHorariaEjemplo {
    public static void main(String[] args) {
        // Hora local del sistema
        LocalDateTime horaLocal = LocalDateTime.now();
        System.out.println("Hora local (sin zona): " + horaLocal);

        // Hora local con información de zona horaria
        ZonedDateTime horaLocalConZona = ZonedDateTime.now();
        System.out.println("Hora local con zona: " + horaLocalConZona);

        // Hora en UTC
        ZonedDateTime horaUTC = ZonedDateTime.now(ZoneId.of("UTC"));
        System.out.println("Hora en UTC: " + horaUTC);

        // También puedes convertir la hora local a UTC
        ZonedDateTime horaLocalConvertidaAUTC = ZonedDateTime.now().withZoneSameInstant(ZoneId.of("UTC"));
        System.out.println("Hora local convertida a UTC: " + horaLocalConvertidaAUTC);
    }
}
