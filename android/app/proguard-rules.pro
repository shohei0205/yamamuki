# 配布版(release)の R8 の規則。
# Room・Ktor(OkHttp)・コルーチン・Compose は、ライブラリが同梱する規則で足りるので、ここには書かない。
# 足りないものが見つかったときだけ、理由と一緒に書き足す。

# クラッシュの記録で行番号を読めるようにする。
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
