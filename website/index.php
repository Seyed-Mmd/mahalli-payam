<?php
declare(strict_types=1);
require __DIR__ . '/lib.php';
start_session();
$page = $_GET['page'] ?? 'home';
$action = $_POST['action'] ?? '';
$registrationOpen = db()->query("SELECT value FROM settings WHERE \"key\"='registration_open'")->fetchColumn() === '1';

if ($action !== '') {
    csrf_check();
    if ($action === 'register') {
        if (db()->query("SELECT value FROM settings WHERE \"key\"='registration_open'")->fetchColumn() !== '1') { flash('ثبت‌نام عمومی بسته است. برای ساخت حساب با مدیر سامانه تماس بگیر.', 'error'); redirect('index.php?page=login'); }
        $username = trim((string)($_POST['username'] ?? ''));
        $password = (string)($_POST['password'] ?? '');
        if (!preg_match('/^[\p{L}\p{N}_.-]{3,32}$/u', $username) || strlen($password) < 12) {
            flash('نام کاربری ۳ تا ۳۲ نویسه و رمز عبور دست‌کم ۱۲ نویسه باشد.', 'error'); redirect('index.php?page=register');
        }
        try {
            $q=db()->prepare('INSERT INTO users(username,password_hash) VALUES(?,?)'); $q->execute([$username,password_hash($password,PASSWORD_DEFAULT)]);
            flash('ثبت‌نام انجام شد. بعد از تأیید ادمین می‌توانی کد اتصال بگیری.'); redirect('index.php?page=login');
        } catch (PDOException $e) { flash('این نام کاربری قبلاً گرفته شده است.', 'error'); redirect('index.php?page=register'); }
    }
    if ($action === 'login') {
        $username=trim((string)($_POST['username']??'')); $password=(string)($_POST['password']??'');
        $q=db()->prepare('SELECT id,password_hash,status FROM users WHERE username=?'); $q->execute([$username]); $u=$q->fetch();
        if (!$u || $u['status']==='suspended' || !password_verify($password,$u['password_hash'])) { flash('نام کاربری یا رمز درست نیست.', 'error'); redirect('index.php?page=login'); }
        session_regenerate_id(true); $_SESSION['user_id']=(int)$u['id']; $_SESSION['csrf']=bin2hex(random_bytes(32)); redirect('index.php?page=panel');
    }
    if ($action === 'logout') { $_SESSION=[]; session_destroy(); redirect('index.php'); }
    if ($action === 'change_password') {
        $u=require_user(); $old=(string)($_POST['old_password']??''); $new=(string)($_POST['new_password']??'');
        $q=db()->prepare('SELECT password_hash FROM users WHERE id=?'); $q->execute([$u['id']]); $hash=$q->fetchColumn();
        if (!$hash || !password_verify($old,$hash) || strlen($new)<12) { flash('رمز فعلی درست نیست یا رمز جدید کوتاه است.', 'error'); redirect('index.php?page=panel'); }
        db()->prepare('UPDATE users SET password_hash=?, must_change_password=0 WHERE id=?')->execute([password_hash($new,PASSWORD_DEFAULT),$u['id']]); flash('رمز تغییر کرد.'); redirect('index.php?page=panel');
    }
    if ($action === 'issue_code') {
        $u=require_user(); if ($u['status']!=='active') { flash('حساب هنوز تأیید نشده است.', 'error'); redirect('index.php?page=panel'); }
        $code=strtoupper(substr(bin2hex(random_bytes(6)),0,10)); $hash=hash_hmac('sha256',$code,app_config()['app_secret']);
        db()->prepare('UPDATE pair_codes SET used_at=CURRENT_TIMESTAMP WHERE user_id=? AND used_at IS NULL')->execute([$u['id']]);
        db()->prepare("INSERT INTO pair_codes(user_id,code_hash,expires_at) VALUES(?,?,datetime('now','+10 minutes'))")->execute([$u['id'],$hash]);
        flash('کد اتصال (فقط ۱۰ دقیقه معتبر است): '.$code); redirect('index.php?page=panel');
    }
    if (in_array($action,['approve','suspend','reset_password','revoke_device','create_user','toggle_registration'],true)) {
        require_admin(); $id=(int)($_POST['id']??0);
        if ($action==='create_user') {
            $username=trim((string)($_POST['username']??'')); $password=(string)($_POST['password']??'');
            if (!preg_match('/^[\p{L}\p{N}_.-]{3,32}$/u',$username) || strlen($password)<12) { flash('نام کاربری باید ۳ تا ۳۲ نویسه و رمز دست‌کم ۱۲ نویسه باشد.','error'); redirect('index.php?page=admin'); }
            try { db()->prepare("INSERT INTO users(username,password_hash,role,status,approved_at) VALUES(?,?,'user','active',CURRENT_TIMESTAMP)")->execute([$username,password_hash($password,PASSWORD_DEFAULT)]); flash('کاربر ساخته و فعال شد.'); }
            catch(PDOException $e) { flash('این نام کاربری قبلاً استفاده شده است.','error'); }
            redirect('index.php?page=admin');
        }
        if ($action==='toggle_registration') { $open=(($_POST['open']??'0')==='1')?'1':'0'; db()->prepare('UPDATE settings SET value=? WHERE "key"=\'registration_open\'')->execute([$open]); flash($open==='1'?'ثبت‌نام عمومی فعال شد.':'ثبت‌نام عمومی بسته شد.'); redirect('index.php?page=admin'); }
        if ($action==='approve') db()->prepare("UPDATE users SET status='active', approved_at=CURRENT_TIMESTAMP WHERE id=? AND role='user'")->execute([$id]);
        if ($action==='suspend') { db()->prepare("UPDATE users SET status='suspended' WHERE id=? AND role='user'")->execute([$id]); db()->prepare("UPDATE devices SET status='revoked' WHERE user_id=?")->execute([$id]); }
        if ($action==='reset_password') { $temporary=bin2hex(random_bytes(7)); db()->prepare('UPDATE users SET password_hash=?,must_change_password=1 WHERE id=? AND role=\'user\'')->execute([password_hash($temporary,PASSWORD_DEFAULT),$id]); flash('رمز موقت (فقط همین بار نمایش داده می‌شود): '.$temporary); redirect('index.php?page=admin'); }
        if ($action==='revoke_device') db()->prepare("UPDATE devices SET status='revoked' WHERE user_id=?")->execute([$id]);
        flash('تغییر ذخیره شد.'); redirect('index.php?page=admin');
    }
}

$user=current_user();
if ($user && !empty($user['must_change_password']) && $page!=='panel') redirect('index.php?page=panel');
if ($page==='home') {
    page_header('دسترسی محلی به گوشی',$user);
    echo '<section class="hero"><div class="eyebrow">خصوصی · محلی · فارسی</div><h1>فایل‌ها و پیام‌های گوشی،<br><span>روی شبکهٔ خودت</span></h1><p class="lead">پنل وب محلی برای پیامک‌ها، مخاطبین، فایل‌ها و گالری. محتوای گوشی روی گوشی می‌ماند؛ هاست فقط حساب و اتصال را مدیریت می‌کند.</p><div class="actions"><a class="button" href="'.h(app_config()['download_url']).'">دانلود اپ اندروید <span>↓</span></a><a class="button secondary" href="index.php?page='.($user?'panel':'login').'">'.($user?'ورود به پنل':($registrationOpen?'ثبت‌نام / ورود':'ورود کاربران')).'</a><button class="button secondary" id="install-pwa" hidden>نصب روی لپ‌تاپ</button></div><div class="hero-note"><span class="pulse"></span> برای دیدن اطلاعات، لپ‌تاپ باید به هات‌اسپات گوشی وصل باشد.</div></section><section class="feature-grid"><article class="feature"><b>۱</b><h3>حساب بساز</h3><p>حساب را مدیر می‌سازد یا ثبت‌نام عمومی را فعال می‌کند.</p></article><article class="feature"><b>۲</b><h3>گوشی را جفت کن</h3><p>کد یک‌بارمصرف سایت را در برنامه وارد کن.</p></article><article class="feature"><b>۳</b><h3>محلی باز کن</h3><p>با مرورگر لپ‌تاپ وارد پنل گوشی شو.</p></article></section>';
    page_footer(); exit;
}
if ($page==='register') {
    page_header('ثبت‌نام',$user); echo '<section class="auth-card"><div class="eyebrow">شروع استفاده</div><h1>ساخت حساب</h1>'.render_flash(); if($registrationOpen) echo '<form method="post"><input type="hidden" name="csrf" value="'.h(csrf_token()).'"><input type="hidden" name="action" value="register"><label>نام کاربری<input name="username" required minlength="3" maxlength="32" autocomplete="username"></label><label>رمز عبور<input name="password" type="password" required minlength="12" autocomplete="new-password"><small>حداقل ۱۲ نویسه. ایمیل لازم نیست.</small></label><button class="button full">ثبت‌نام</button></form><p class="muted">حساب بعد از تأیید مدیر فعال می‌شود.</p>'; else echo '<p class="notice">ثبت‌نام عمومی بسته است. برای ساخت حساب با مدیر سامانه تماس بگیر.</p>';
    page_footer(); exit;
}
if ($page==='login') {
    page_header('ورود',$user); echo '<section class="auth-card"><div class="eyebrow">خوش برگشتی</div><h1>ورود به حساب</h1>'.render_flash().'<form method="post"><input type="hidden" name="csrf" value="'.h(csrf_token()).'"><input type="hidden" name="action" value="login"><label>نام کاربری<input name="username" required autocomplete="username"></label><label>رمز عبور<input name="password" type="password" required autocomplete="current-password"></label><button class="button full">ورود</button></form><p class="muted">رمز را فراموش کردی؟ از ادمین بخواه رمز موقت صادر کند.</p></section>';
    page_footer(); exit;
}
if ($page==='panel') {
    $u=require_user(); page_header('پنل حساب',$u); echo '<section class="page-heading"><div><div class="eyebrow">حساب کاربری</div><h1>سلام، '.h($u['username']).'</h1></div><span class="status '.h($u['status']).'">'.($u['status']==='active'?'فعال':($u['status']==='pending'?'در انتظار تأیید':'تعلیق‌شده')).'</span></section>'.render_flash();
    if ($u['status']==='pending') echo '<section class="notice"><h2>درخواستت در صف بررسی است</h2><p>بعد از تأیید مدیر، از همین صفحه کد جفت‌سازی می‌گیری.</p></section>';
    elseif ($u['status']==='suspended') echo '<section class="notice danger"><h2>حساب غیرفعال است</h2><p>برای پیگیری با ادمین تماس بگیر.</p></section>';
    else {
        $q=db()->prepare("SELECT device_id,paired_at,last_seen FROM devices WHERE user_id=? AND status='active' ORDER BY id DESC LIMIT 1"); $q->execute([$u['id']]); $device=$q->fetch();
        echo '<section class="card"><div class="card-title"><div><div class="eyebrow">اتصال اندروید</div><h2>'.($device?'گوشی جفت شده':'گوشی هنوز جفت نشده').'</h2></div><span class="device-dot '.($device?'on':'') .'" /></div><p class="muted">کد را در برنامهٔ اندروید وارد کن. کد یک بار استفاده می‌شود و ۱۰ دقیقه اعتبار دارد.</p><form method="post"><input type="hidden" name="csrf" value="'.h(csrf_token()).'"><input type="hidden" name="action" value="issue_code"><button class="button">'.($device?'جفت‌سازی دوباره':'دریافت کد اتصال').'</button></form>'.($device?'<p class="muted">آخرین اتصال به سایت: '.h($device['last_seen']?:'هنوز ثبت نشده').'</p>':'').'</section>';
    }
    if (!empty($u['must_change_password'])) echo '<div class="notice danger"><h2>رمز موقت را عوض کن</h2><p>برای ادامه، رمز جدیدی تعیین کن.</p></div>';
    echo '<section class="card"><div class="eyebrow">امنیت حساب سایت</div><h2>تغییر رمز</h2><form method="post" class="inline-form"><input type="hidden" name="csrf" value="'.h(csrf_token()).'"><input type="hidden" name="action" value="change_password"><label>رمز فعلی<input name="old_password" type="password" required></label><label>رمز جدید<input name="new_password" type="password" minlength="12" required></label><button class="button secondary">ذخیره</button></form></section>';
    page_footer(); exit;
}
if ($page==='admin') {
    require_admin(); page_header('مدیریت',$user); echo '<section class="page-heading"><div><div class="eyebrow">مدیریت سامانه</div><h1>حساب‌ها</h1></div></section>'.render_flash();
    echo '<section class="card"><div class="eyebrow">ساخت حساب</div><h2>افزودن کاربر</h2><form method="post" class="inline-form"><input type="hidden" name="csrf" value="'.h(csrf_token()).'"><input type="hidden" name="action" value="create_user"><label>نام کاربری<input name="username" required minlength="3" maxlength="32"></label><label>رمز اولیه<input name="password" type="password" required minlength="12"></label><button class="button">ساخت کاربر فعال</button></form></section>';
    echo '<section class="card"><h2>ثبت‌نام عمومی</h2><p class="muted">وضعیت فعلی: '.($registrationOpen?'فعال':'بسته').'</p><form method="post"><input type="hidden" name="csrf" value="'.h(csrf_token()).'"><input type="hidden" name="action" value="toggle_registration"><input type="hidden" name="open" value="'.($registrationOpen?'0':'1').'"> <button class="button secondary">'.($registrationOpen?'بستن ثبت‌نام':'فعال‌کردن ثبت‌نام').'</button></form></section>';
    $users=db()->query("SELECT u.id,u.username,u.status,u.created_at,d.device_id,d.last_seen,d.status device_status FROM users u LEFT JOIN devices d ON d.id=(SELECT MAX(id) FROM devices WHERE user_id=u.id) WHERE u.role='user' ORDER BY u.created_at DESC")->fetchAll();
    echo '<div class="table-wrap"><table><thead><tr><th>کاربر</th><th>وضعیت</th><th>ثبت‌نام</th><th>اتصال</th><th>مدیریت</th></tr></thead><tbody>';
    foreach($users as $r){ echo '<tr><td><strong>'.h($r['username']).'</strong></td><td><span class="status '.h($r['status']).'">'.h($r['status']).'</span></td><td>'.h($r['created_at']).'</td><td>'.($r['device_id']?h(substr($r['device_id'],0,8)).'…':'—').'</td><td class="row-actions">';
        if($r['status']!=='active') echo admin_form('approve',(int)$r['id'],'تأیید');
        if($r['status']==='active') echo admin_form('suspend',(int)$r['id'],'تعلیق','danger-button');
        echo admin_form('reset_password',(int)$r['id'],'رمز موقت','secondary tiny');
        if($r['device_id'] && $r['device_status']==='active') echo admin_form('revoke_device',(int)$r['id'],'قطع گوشی','secondary tiny');
        echo '</td></tr>'; }
    if(!$users) echo '<tr><td colspan="5" class="muted">هنوز حسابی ثبت نشده است.</td></tr>';
    echo '</tbody></table></div><p class="muted">رمز موقت فقط یک بار نمایش داده می‌شود و کاربر باید پس از ورود آن را عوض کند.</p>';
    page_footer(); exit;
}
http_response_code(404); page_header('یافت نشد',$user); echo '<section class="notice"><h1>این صفحه پیدا نشد</h1><a href="index.php">بازگشت</a></section>'; page_footer();

function render_flash(): string { $f=take_flash(); return $f?'<div class="flash '.h($f[1]).'">'.h($f[0]).'</div>':''; }
function admin_form(string $action,int $id,string $label,string $class='secondary tiny'): string { return '<form method="post"><input type="hidden" name="csrf" value="'.h(csrf_token()).'"><input type="hidden" name="action" value="'.h($action).'"><input type="hidden" name="id" value="'.$id.'"><button class="'.$class.'">'.h($label).'</button></form>'; }
