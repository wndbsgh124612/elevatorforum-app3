<?php
include_once('../../common.php');

if (isset($_POST['token']) && trim($_POST['token'])) {
    $user_idx = '';
    if (isset($member['mb_id']) && $member['mb_id']) {
        $user_idx = $member['mb_id'];
    }
    if (!$user_idx && isset($_POST['user_idx']) && $_POST['user_idx']) {
        $user_idx = trim($_POST['user_idx']);
    }
    if (!$user_idx) {
        exit;
    }

    $token = trim($_POST['token']);

    $rows = sql_fetch(" SELECT COUNT(*) AS cnt
                          FROM rb_app_token
                         WHERE mb_id = '{$user_idx}' ");

    if (isset($rows['cnt']) && $rows['cnt'] > 0) {
        $sql = " UPDATE rb_app_token
                    SET tk_token = '{$token}',
                        tk_and = 'and',
                        mb_id = '{$user_idx}',
                        tk_datetime = '".G5_TIME_YMDHIS."'
                  WHERE mb_id = '{$user_idx}' ";
        sql_query($sql);
    } else {
        $sql = " INSERT INTO rb_app_token
                    SET tk_token = '{$token}',
                        tk_and = 'and',
                        mb_id = '{$user_idx}',
                        tk_datetime = '".G5_TIME_YMDHIS."' ";
        sql_query($sql);
    }
}
?>