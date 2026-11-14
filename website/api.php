<?php
declare(strict_types=1);
require __DIR__ . '/lib.php';
if ($_SERVER['REQUEST_METHOD'] !== 'POST') json_response(405,['error'=>'method_not_allowed']);
$input=json_decode(file_get_contents('php://input'),true); if(!is_array($input)) $input=$_POST;
$action=(string)($input['action']??'');
if ($action==='claim') {
    $code=strtoupper(preg_replace('/[^A-Za-z0-9]/','',(string)($input['code']??'')));
    $device=substr((string)($input['device_id']??''),0,36);
    if(strlen($code)<8 || !preg_match('/^[a-f0-9-]{36}$/i',$device)) json_response(422,['error'=>'invalid_input']);
    $hash=hash_hmac('sha256',$code,app_config()['app_secret']); $pdo=db(); $pdo->beginTransaction();
    try {
        $q=$pdo->prepare("SELECT p.id,p.user_id,u.status FROM pair_codes p JOIN users u ON u.id=p.user_id WHERE p.code_hash=? AND p.used_at IS NULL AND p.expires_at>datetime('now')"); $q->execute([$hash]); $p=$q->fetch();
        if(!$p || $p['status']!=='active') { $pdo->rollBack(); json_response(403,['error'=>'code_invalid_or_account_pending']); }
        $pdo->prepare('UPDATE pair_codes SET used_at=CURRENT_TIMESTAMP WHERE id=?')->execute([$p['id']]);
        $pdo->prepare("UPDATE devices SET status='revoked' WHERE user_id=? AND status='active'")->execute([$p['user_id']]);
        $token=bin2hex(random_bytes(32)); $pdo->prepare("INSERT INTO devices(user_id,device_id,token_hash) VALUES(?,?,?) ON CONFLICT(user_id,device_id) DO UPDATE SET token_hash=excluded.token_hash,status='active',paired_at=CURRENT_TIMESTAMP,last_seen=NULL")->execute([$p['user_id'],$device,hash('sha256',$token)]);
        $pdo->commit(); json_response(200,['ok'=>true,'device_token'=>$token,'account_id'=>(int)$p['user_id']]);
    } catch(Throwable $e) { if($pdo->inTransaction())$pdo->rollBack(); json_response(500,['error'=>'server_error']); }
}
if ($action==='status') {
    $token=(string)($input['device_token']??''); $device=verify_device($token);
    if(!$device) json_response(403,['ok'=>false,'error'=>'device_revoked_or_account_inactive']);
    json_response(200,['ok'=>true,'account_id'=>(int)$device['user_id'],'checked_at'=>gmdate(DATE_ATOM)]);
}
json_response(404,['error'=>'unknown_action']);
