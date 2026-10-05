package io.github.shohei0205.yamamuki.data

import androidx.room.migration.Migration

/**
 * 山データの DB を古い版から新しい版へ移す処理のうち、手で書くもの。
 *
 * DB の版([MountainDatabase.VERSION])を上げるときは、同じ PR で移行も入れる。
 * - テーブルや列を足すだけなら、@Database の autoMigrations に AutoMigration(from, to) を足す(ここには書かない)。
 * - 列の名前を変える・消すなど、Room が自動で作れない移行は、ここに Migration(from, to) を足す。
 * - 事前ダウンロードした山(mountains と fetched_tiles)は移行で消さない。取り直せるテーブルだけは作り直してよい。
 * どの移行も MountainDatabaseMigrationTest で、書き出した過去の版のスキーマから確かめる。
 */
val MOUNTAIN_MIGRATIONS: Array<Migration> = arrayOf()
