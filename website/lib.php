<?php
declare(strict_types=1);

function database_path(): string {
    $site=realpath(__DIR__)?:__DIR__; $documentRoot=realpath((string)($_SERVER['DOCUMENT_ROOT']??''));
    $publicRoot=$documentRoot && ($site===$documentRoot || str_starts_with($site,rtrim($documentRoot,DIRECTORY_SEPARATOR).DIRECTORY_SEPARATOR));
    $base=$publicRoot?dirname($documentRoot):dirname($site);
    return $base.DIRECTORY_SEPARATOR.'mahalli-payam-data-'.substr(hash('sha256',$site),0,10).DIRECTORY_SEPARATOR.'app.sqlite';
}
function sqlite_available(): bool {
    if (!class_exists(PDO::class)) return false;
    try { return in_array('sqlite',PDO::getAvailableDrivers(),true); }
    catch (Throwable $e) { return false; }
}
function sqlite_runtime_details(): string {
    $drivers=class_exists(PDO::class)?PDO::getAvailableDrivers():[];
    return 'PHP '.PHP_VERSION.' / '.PHP_SAPI.' / PDO: '.($drivers?implode(', ',$drivers):'no drivers');
}
function db(): PDO {
    static $pdo = null;
    if ($pdo instanceof PDO) return $pdo;
    $file = database_path();
    if (!is_file($file)) { http_response_code(503); exit('سامانه هنوز نصب نشده است. ابتدا install.php را اجرا کنید.'); }
    if (!sqlite_available()) { http_response_code(500); exit('درایور SQLite برای PDO در این اجرای PHP در دسترس نیست. '.htmlspecialchars(sqlite_runtime_details(),ENT_QUOTES,'UTF-8')); }
    $pdo = new PDO('sqlite:' . $file, null, null, [PDO::ATTR_ERRMODE=>PDO::ERRMODE_EXCEPTION, PDO::ATTR_DEFAULT_FETCH_MODE=>PDO::FETCH_ASSOC]);
    $pdo->exec('PRAGMA foreign_keys = ON');
    $pdo->exec('PRAGMA busy_timeout = 5000');
    return $pdo;
}
function app_config(): array { return ['site_name'=>'پیام محلی','app_secret'=>db()->query("SELECT value FROM settings WHERE \"key\"='app_secret'")->fetchColumn(),'download_url'=>'downloads/mahalli-payam.apk']; }
function start_session(): void {
    if (session_status() === PHP_SESSION_ACTIVE) return;
    $secure = (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off');
    session_set_cookie_params(['lifetime'=>0,'path'=>'/','secure'=>$secure,'httponly'=>true,'samesite'=>'Lax']);
    session_start();
}
function h(string $s): string { return htmlspecialchars($s, ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8'); }
function csrf_token(): string { start_session(); if (empty($_SESSION['csrf'])) $_SESSION['csrf']=bin2hex(random_bytes(32)); return $_SESSION['csrf']; }
function csrf_check(): void { start_session(); $got=$_POST['csrf']??''; if (!$got || !hash_equals($_SESSION['csrf']??'',(string)$got)) { http_response_code(419); exit('درخواست منقضی شد. صفحه را تازه کنید.'); } }
function current_user(): ?array {
    start_session(); if (empty($_SESSION['user_id'])) return null;
    $q=db()->prepare('SELECT id,username,role,status,must_change_password FROM users WHERE id=?'); $q->execute([$_SESSION['user_id']]); $u=$q->fetch();
    if (!$u || $u['status']==='suspended') { $_SESSION=[]; session_destroy(); return null; }
    return $u;
}
function require_user(): array { $u=current_user(); if (!$u) { header('Location: index.php?page=login'); exit; } return $u; }
function require_admin(): array { $u=require_user(); if ($u['role']!=='admin') { http_response_code(403); exit('دسترسی مدیر لازم است.'); } return $u; }
function flash(string $text,string $kind='ok'): void { start_session(); $_SESSION['flash']=[$text,$kind]; }
function take_flash(): ?array { start_session(); $f=$_SESSION['flash']??null; unset($_SESSION['flash']); return $f; }
function redirect(string $to): never { header('Location: '.$to); exit; }
function verify_device(string $token): ?array {
    if (strlen($token)<32) return null;
    $q=db()->prepare('SELECT d.id device_id,d.user_id,d.status device_status,u.status user_status FROM devices d JOIN users u ON u.id=d.user_id WHERE d.token_hash=? LIMIT 1');
    $q->execute([hash('sha256',$token)]); $row=$q->fetch();
    if (!$row || $row['device_status']!=='active' || $row['user_status']!=='active') return null;
    db()->prepare("UPDATE devices SET last_seen=CURRENT_TIMESTAMP WHERE id=?")->execute([$row['device_id']]);
    return $row;
}
function json_response(int $status,array $data): never { http_response_code($status); header('Content-Type: application/json; charset=utf-8'); header('Cache-Control: no-store'); echo json_encode($data,JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES); exit; }
function page_header(string $title,?array $user=null): void {
    echo '<!doctype html><html lang="fa" dir="rtl"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="theme-color" content="#f5f7f6"><link rel="manifest" href="manifest.webmanifest"><link rel="icon" href="assets/icon.svg" type="image/svg+xml"><title>'.h($title).' · پیام محلی</title><link rel="stylesheet" href="assets/style.css"></head><body>';
    echo '<nav class="nav"><a class="brand" href="index.php"><span class="brand-mark">م</span><span>پیام محلی</span></a><div class="nav-links">';
    if ($user) { echo '<a href="index.php?page=panel">پنل</a>'; if ($user['role']==='admin') echo '<a href="index.php?page=admin">مدیریت</a>'; echo '<form method="post" action="index.php"><input type="hidden" name="csrf" value="'.h(csrf_token()).'"><input type="hidden" name="action" value="logout"><button class="link-button">خروج</button></form>'; }
    else { echo '<a href="index.php?page=login">ورود</a>'; if (db()->query("SELECT value FROM settings WHERE \"key\"='registration_open'")->fetchColumn()==='1') echo '<a class="button small" href="index.php?page=register">ثبت‌نام</a>'; }
    echo '</div></nav><main class="container">';
}
function page_footer(): void { echo '</main><footer class="footer">حساب و دسترسی روی هاست مدیریت می‌شود؛ اطلاعات گوشی فقط در شبکهٔ محلی می‌ماند.</footer><script src="assets/site.js" defer></script></body></html>'; }
