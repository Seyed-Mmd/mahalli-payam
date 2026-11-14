<?php
declare(strict_types=1);
require __DIR__.'/lib.php';
session_start();
$dbFile=database_path();
if (is_file($dbFile)) { http_response_code(404); exit('سامانه قبلاً نصب شده است.'); }
if (!sqlite_available()) { http_response_code(500); exit('درایور SQLite برای PDO در اجرای وب این دامنه در دسترس نیست. '.htmlspecialchars(sqlite_runtime_details(),ENT_QUOTES,'UTF-8').' تنظیمات PHP همین دامنه و نسخهٔ انتخاب‌شده را بررسی کن.'); }
if (empty($_SESSION['install_csrf'])) $_SESSION['install_csrf']=bin2hex(random_bytes(32));
$error='';
if ($_SERVER['REQUEST_METHOD']==='POST') {
    if (!hash_equals($_SESSION['install_csrf'],(string)($_POST['csrf']??''))) { http_response_code(419); exit('صفحه منقضی شد؛ دوباره بازش کنید.'); }
    $username=trim((string)($_POST['username']??'')); $password=(string)($_POST['password']??''); $confirm=(string)($_POST['confirm']??'');
    if (!preg_match('/^[\p{L}\p{N}_.-]{3,32}$/u',$username)) $error='نام کاربری باید ۳ تا ۳۲ نویسه باشد.';
    elseif (strlen($password)<12) $error='رمز مدیر باید دست‌کم ۱۲ نویسه باشد.';
    elseif (!hash_equals($password,$confirm)) $error='تکرار رمز یکسان نیست.';
    else {
        try {
            $dataDir=dirname($dbFile);
            if (!is_dir($dataDir) && !mkdir($dataDir,0700,true)) throw new RuntimeException('ساخت پوشهٔ دیتابیس ممکن نشد.');
            $pdo=new PDO('sqlite:'.$dbFile,null,null,[PDO::ATTR_ERRMODE=>PDO::ERRMODE_EXCEPTION]); $pdo->exec('PRAGMA foreign_keys = ON');
            $pdo->beginTransaction();
            $pdo->exec("CREATE TABLE users(id INTEGER PRIMARY KEY AUTOINCREMENT,username TEXT NOT NULL COLLATE NOCASE UNIQUE,password_hash TEXT NOT NULL,role TEXT NOT NULL DEFAULT 'user' CHECK(role IN ('user','admin')),status TEXT NOT NULL DEFAULT 'pending' CHECK(status IN ('pending','active','suspended')),must_change_password INTEGER NOT NULL DEFAULT 0,created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,approved_at TEXT NULL)");
            $pdo->exec("CREATE TABLE pair_codes(id INTEGER PRIMARY KEY AUTOINCREMENT,user_id INTEGER NOT NULL,code_hash TEXT NOT NULL UNIQUE,expires_at TEXT NOT NULL,used_at TEXT NULL,created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE)");
            $pdo->exec("CREATE TABLE devices(id INTEGER PRIMARY KEY AUTOINCREMENT,user_id INTEGER NOT NULL,device_id TEXT NOT NULL,token_hash TEXT NOT NULL UNIQUE,status TEXT NOT NULL DEFAULT 'active' CHECK(status IN ('active','revoked')),paired_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,last_seen TEXT NULL,UNIQUE(user_id,device_id),FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE)");
            $pdo->exec('CREATE TABLE settings("key" TEXT PRIMARY KEY,value TEXT NOT NULL)');
            $ins=$pdo->prepare('INSERT INTO users(username,password_hash,role,status,approved_at) VALUES(?,?,\'admin\',\'active\',CURRENT_TIMESTAMP)'); $ins->execute([$username,password_hash($password,PASSWORD_DEFAULT)]);
            $setting=$pdo->prepare('INSERT INTO settings("key",value) VALUES(?,?)'); $setting->execute(['app_secret',bin2hex(random_bytes(48))]); $setting->execute(['registration_open','0']);
            $pdo->commit(); @chmod($dbFile,0600); session_regenerate_id(true); $_SESSION=[];
            header('Location: index.php?page=login&installed=1'); exit;
        } catch (Throwable $e) { if (isset($pdo)&&$pdo->inTransaction()) $pdo->rollBack(); if (is_file($dbFile)) @unlink($dbFile); $error='نصب کامل نشد. دسترسی نوشتن پوشهٔ data و فعال بودن PDO SQLite را بررسی کن.'; }
    }
}
?><!doctype html><html lang="fa" dir="rtl"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><link rel="stylesheet" href="assets/style.css"><title>نصب پیام محلی</title><main class="container"><section class="auth-card"><div class="eyebrow">راه‌اندازی اولیه</div><h1>ساخت مدیر سامانه</h1><p class="muted">نام کاربری و رمز اینجا برای ورود مدیر ساخته می‌شوند. دیتابیس SQLite خودکار ایجاد می‌شود.</p><?php if($error!=='') echo '<div class="flash error">'.htmlspecialchars($error,ENT_QUOTES,'UTF-8').'</div>'; ?><form method="post"><input type="hidden" name="csrf" value="<?=htmlspecialchars($_SESSION['install_csrf'],ENT_QUOTES,'UTF-8')?>"><label>نام کاربری مدیر<input name="username" required minlength="3" maxlength="32" autocomplete="username"></label><label>رمز مدیر<input name="password" type="password" required minlength="12" autocomplete="new-password"></label><label>تکرار رمز<input name="confirm" type="password" required minlength="12" autocomplete="new-password"></label><button class="button full">نصب و ساخت مدیر</button></form></section></main></html>
