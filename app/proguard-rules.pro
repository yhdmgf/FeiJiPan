# Release 使用 R8。只保留运行时确实依赖稳定名称的边界。

# kotlinx.serialization 生成代码依赖序列化注解和模型成员。
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,Signature,InnerClasses,EnclosingMethod
-keep @kotlinx.serialization.Serializable class com.panclone.app.** { *; }
