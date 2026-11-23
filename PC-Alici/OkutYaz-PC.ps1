# Okut-Yaz - Windows alici program (Wi-Fi)
# Telefondan gelen metni, o an acik olan pencereye klavye gibi yazar.
# Calistirma: OkutYaz-PC-Baslat.bat dosyasina cift tikla.

$port = 8765
$pin = (Get-Random -Minimum 1000 -Maximum 9999).ToString()
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;

public static class KB {
    [StructLayout(LayoutKind.Sequential)]
    struct MOUSEINPUT { public int dx; public int dy; public uint mouseData; public uint dwFlags; public uint time; public IntPtr dwExtraInfo; }
    [StructLayout(LayoutKind.Sequential)]
    struct KEYBDINPUT { public ushort wVk; public ushort wScan; public uint dwFlags; public uint time; public IntPtr dwExtraInfo; }
    [StructLayout(LayoutKind.Explicit)]
    struct INPUTUNION { [FieldOffset(0)] public MOUSEINPUT mi; [FieldOffset(0)] public KEYBDINPUT ki; }
    [StructLayout(LayoutKind.Sequential)]
    struct INPUT { public uint type; public INPUTUNION u; }

    [DllImport("user32.dll", SetLastError = true)]
    static extern uint SendInput(uint nInputs, INPUT[] pInputs, int cbSize);

    static void Send(ushort vk, ushort scan, uint flags) {
        INPUT[] a = new INPUT[1];
        a[0].type = 1;
        a[0].u.ki.wVk = vk;
        a[0].u.ki.wScan = scan;
        a[0].u.ki.dwFlags = flags;
        SendInput(1, a, Marshal.SizeOf(typeof(INPUT)));
    }

    public static void Tap(ushort vk) {
        Send(vk, 0, 0);
        Send(vk, 0, 2);
    }

    public static void TypeText(string s) {
        foreach (char c in s) {
            if (c == '\r') continue;
            if (c == '\n') { Tap(0x0D); continue; }
            Send(0, (ushort)c, 4);   // KEYEVENTF_UNICODE
            Send(0, (ushort)c, 6);   // KEYEVENTF_UNICODE | KEYEVENTF_KEYUP
            System.Threading.Thread.Sleep(3);
        }
    }
}
'@

$listener = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Any, $port)
try {
    $listener.Start()
} catch {
    Write-Host "Port $port acilamadi: $($_.Exception.Message)" -ForegroundColor Red
    Write-Host "Baska bir Okut-Yaz penceresi acik olabilir."
    exit 1
}

Write-Host ""
Write-Host "=== Okut-Yaz - PC alici ===" -ForegroundColor Cyan
Write-Host ""
Write-Host "Telefon uygulamasinda Ayarlar > Wi-Fi ile baglanti bolumune sunlari gir:"
Write-Host ""
try {
    $ips = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction Stop | Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' }
    foreach ($ip in $ips) { Write-Host ("  IP adresi : " + $ip.IPAddress) -ForegroundColor Yellow }
} catch {
    Write-Host "  IP adresi : (ipconfig komutuyla IPv4 adresine bak)" -ForegroundColor Yellow
}
Write-Host ("  Kod       : " + $pin) -ForegroundColor Yellow
Write-Host ""
Write-Host "Telefon ve bilgisayar ayni Wi-Fi aginda olmali."
Write-Host "Bu pencereyi acik tut. Kapatmak icin pencereyi kapat."
Write-Host ""

while ($true) {
    while (-not $listener.Pending()) { Start-Sleep -Milliseconds 200 }
    $client = $listener.AcceptTcpClient()
    try {
        $client.ReceiveTimeout = 5000
        $stream = $client.GetStream()
        $reader = New-Object System.IO.StreamReader($stream, [System.Text.Encoding]::UTF8)
        $writer = New-Object System.IO.StreamWriter($stream, (New-Object System.Text.UTF8Encoding($false)))
        $writer.NewLine = "`n"
        $writer.AutoFlush = $true

        $code = $reader.ReadLine()
        $b64 = $reader.ReadLine()
        $after = $reader.ReadLine()

        if ($code -ne $pin) {
            $writer.WriteLine("ERR:kod")
            Write-Host "Yanlis kod ile baglanti denemesi." -ForegroundColor Red
        } else {
            $text = ""
            if ($b64) { $text = [System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($b64)) }
            if ($text.Length -gt 0) {
                Start-Sleep -Milliseconds 150
                [KB]::TypeText($text)
                if ($after -eq "enter") { [KB]::Tap(0x0D) } elseif ($after -eq "tab") { [KB]::Tap(0x09) }
                Write-Host ((Get-Date).ToString("HH:mm:ss") + "  Yazildi: " + $text.Length + " karakter")
            } else {
                Write-Host ((Get-Date).ToString("HH:mm:ss") + "  Telefon baglandi (test).")
            }
            $writer.WriteLine("OK")
        }
    } catch {
        Write-Host ("Hata: " + $_.Exception.Message) -ForegroundColor Red
    } finally {
        $client.Close()
    }
}
