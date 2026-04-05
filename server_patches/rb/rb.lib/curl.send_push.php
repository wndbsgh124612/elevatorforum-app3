<?php
include_once('../../common.php');

function ef_get_fcm_access_token($jsonKeyFilePath) {
    if (!function_exists('getAccessToken')) {
        http_response_code(500);
        echo json_encode(['error' => 'getAccessToken function not available']);
        exit;
    }
    return getAccessToken($jsonKeyFilePath);
}

function ef_send_push_notification($tokens, $title, $body, $jsonKeyFilePath, $url = '', $category = 'general', $roomId = '') {
    $app = sql_fetch(" SELECT * FROM rb_app LIMIT 1 ");
    if (!$app || empty($app['ap_pid'])) {
        http_response_code(500);
        echo json_encode(['error' => 'rb_app.ap_pid missing']);
        exit;
    }

    $tokens = array_values(array_unique(array_filter(array_map('trim', (array)$tokens))));
    if (!$tokens) {
        echo json_encode(['ok' => true, 'sent' => 0]);
        exit;
    }

    $accessToken = ef_get_fcm_access_token($jsonKeyFilePath);
    $url = $url ?: G5_URL;
    $sent = 0;

    foreach ($tokens as $token) {
        $payload = [
            'message' => [
                'token' => $token,
                'data' => [
                    'title' => (string)$title,
                    'body' => (string)$body,
                    'url' => (string)$url,
                    'category' => (string)$category,
                    'room_id' => (string)$roomId,
                ],
                'android' => [
                    'priority' => 'HIGH',
                    'notification' => [
                        'channel_id' => 'elevator_forum_alert_v13',
                        'sound' => 'default',
                    ],
                ],
            ],
        ];

        $ch = curl_init();
        curl_setopt($ch, CURLOPT_URL, 'https://fcm.googleapis.com/v1/projects/' . $app['ap_pid'] . '/messages:send');
        curl_setopt($ch, CURLOPT_POST, true);
        curl_setopt($ch, CURLOPT_RETURNTRANSFER, true);
        curl_setopt($ch, CURLOPT_POSTFIELDS, json_encode($payload, JSON_UNESCAPED_UNICODE));
        curl_setopt($ch, CURLOPT_HTTPHEADER, [
            'Authorization: Bearer ' . $accessToken,
            'Content-Type: application/json',
        ]);
        $response = curl_exec($ch);
        $httpCode = curl_getinfo($ch, CURLINFO_HTTP_CODE);
        curl_close($ch);

        if ($httpCode >= 200 && $httpCode < 300) {
            $sent++;
        }
    }

    echo json_encode(['ok' => true, 'sent' => $sent]);
}

$input = file_get_contents('php://input');
$data = json_decode($input, true);

if (!isset($data['tokens'], $data['title'], $data['body'], $data['jsonKeyFilePath'])) {
    http_response_code(400);
    echo json_encode(['error' => 'Invalid input']);
    exit;
}

ef_send_push_notification(
    $data['tokens'],
    $data['title'],
    $data['body'],
    $data['jsonKeyFilePath'],
    isset($data['url']) ? $data['url'] : G5_URL,
    isset($data['category']) ? $data['category'] : 'general',
    isset($data['room_id']) ? $data['room_id'] : ''
);
