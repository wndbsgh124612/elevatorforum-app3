<?php
if (!defined('_GNUBOARD_')) exit;

function ef_push_escape($value) {
    return sql_real_escape_string((string)$value);
}

function ef_push_get_tokens_by_members($mbIds) {
    $mbIds = array_values(array_unique(array_filter(array_map('trim', (array)$mbIds))));
    if (!$mbIds) return [];

    $safe = [];
    foreach ($mbIds as $mbId) {
        $safe[] = "'" . ef_push_escape($mbId) . "'";
    }

    $sql = " SELECT tk_token
               FROM rb_app_token
              WHERE mb_id IN (" . implode(',', $safe) . ")
                AND tk_token <> '' ";
    $result = sql_query($sql, false);
    $tokens = [];

    if ($result) {
        while ($row = sql_fetch_array($result)) {
            if (!empty($row['tk_token'])) {
                $tokens[] = trim($row['tk_token']);
            }
        }
    }

    return array_values(array_unique($tokens));
}

function ef_push_post_to_gateway($payload) {
    $gateway = G5_URL . '/rb/rb.lib/curl.send_push.php';

    $ch = curl_init();
    curl_setopt($ch, CURLOPT_URL, $gateway);
    curl_setopt($ch, CURLOPT_POST, true);
    curl_setopt($ch, CURLOPT_RETURNTRANSFER, true);
    curl_setopt($ch, CURLOPT_TIMEOUT, 8);
    curl_setopt($ch, CURLOPT_HTTPHEADER, ['Content-Type: application/json; charset=utf-8']);
    curl_setopt($ch, CURLOPT_POSTFIELDS, json_encode($payload, JSON_UNESCAPED_UNICODE));
    $response = curl_exec($ch);
    curl_close($ch);

    return $response;
}

function ef_push_send_chat($receiverMbIds, $senderName, $body, $roomId, $url, $jsonKeyFilePath) {
    $tokens = ef_push_get_tokens_by_members($receiverMbIds);
    if (!$tokens) return false;

    $senderName = trim((string)$senderName);
    $title = $senderName !== '' ? $senderName : '새 채팅';
    $body = trim((string)$body);
    if ($body === '') {
        $body = '새 메시지가 도착했습니다.';
    }

    return ef_push_post_to_gateway([
        'tokens' => $tokens,
        'title' => $title,
        'body' => $body,
        'url' => $url,
        'room_id' => (string)$roomId,
        'category' => 'chat',
        'jsonKeyFilePath' => $jsonKeyFilePath,
    ]);
}

function ef_friend_send_system_note($recvMbId, $sendMbId, $content) {
    if (!$recvMbId || !$sendMbId || !$content) return false;

    if (function_exists('insert_memo')) {
        insert_memo($recvMbId, $sendMbId, $content);
        return true;
    }

    return false;
}
