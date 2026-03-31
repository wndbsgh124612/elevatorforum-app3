<?php
include_once('../../common.php');

function sendPushNotification($tokens, $title, $body, $jsonKeyFilePath) {
    $app = sql_fetch("SELECT * FROM rb_app");
    $accessToken = getAccessToken($jsonKeyFilePath);

    foreach ($tokens as $token) {
        $data = [
            "message" => [
                "token" => $token,
                "data" => [
                    "title" => (string)$title,
                    "body"  => (string)$body,
                    "url"   => G5_URL
                ],
                "android" => [
                    "priority" => "HIGH"
                ]
            ]
        ];

        $ch = curl_init();
        curl_setopt($ch, CURLOPT_URL, 'https://fcm.googleapis.com/v1/projects/' . $app['ap_pid'] . '/messages:send');
        curl_setopt($ch, CURLOPT_POST, true);
        curl_setopt($ch, CURLOPT_RETURNTRANSFER, true);
        curl_setopt($ch, CURLOPT_POSTFIELDS, json_encode($data, JSON_UNESCAPED_UNICODE));
        curl_setopt($ch, CURLOPT_HTTPHEADER, [
            'Authorization: Bearer ' . $accessToken,
            'Content-Type: application/json',
        ]);
        curl_exec($ch);
        curl_close($ch);
    }
}

$input = file_get_contents('php://input');
$data = json_decode($input, true);

if (!isset($data['tokens'], $data['title'], $data['body'], $data['jsonKeyFilePath'])) {
    http_response_code(400);
    echo json_encode(['error' => 'Invalid input']);
    exit;
}

sendPushNotification($data['tokens'], $data['title'], $data['body'], $data['jsonKeyFilePath']);
?>