# Integrar a captura em outro app

Este projeto é uma implementação de referência. O APK não oferece um serviço público para outros aplicativos consumirem seus quadros; incorpore os componentes no seu app e solicite autorização própria ao Shizuku.

Antes de integrar, confirme que a central tem o cliente Qualcomm e o formato descritos em [CAMERA.md](CAMERA.md). A licença do projeto é [AGPL-3.0](../LICENSE); preserve também o [aviso MIT da Shizuku API](../LICENSES/Shizuku-API.txt) na distribuição.

## Copiar o caminho funcional

Copie os arquivos de `app/src/main/java/br/com/redesurftank/havalcamera`, o AIDL em `app/src/main/aidl` e os arquivos nativos em `app/src/main/cpp`. A Activity já mostra autorização, conexão, erros e ciclo de vida; use-a primeiro como tela de referência antes de embutir o preview em uma tela existente.

| Componente | Responsabilidade |
| --- | --- |
| `CameraActivity` | Autorizar Shizuku, abrir o serviço, ler quadros e parar ao sair da tela. |
| `CameraService` | Validar caminhos, executar o cliente como root e devolver o pipe. |
| `ICameraStream.aidl` | Contrato Binder entre app e UserService. |
| `CameraPreview` | Renderizar BGR24 em OpenGL ES 2.0. |
| `camera_stream.c` | Trocar a escrita dos dumps por escrita no pipe. |

Manter o pacote Java original evita renomear imports e o AIDL. O `applicationId` do APK pode ser outro, mas exige as adaptações de caminhos descritas abaixo. `CameraPreview`, seu construtor e seus métodos são package-private; mantenha o código que o usa no mesmo pacote ou exponha apenas o necessário ao incorporá-lo em outro pacote.

## Dependências e biblioteca nativa

Em um app Gradle existente, use Maven Central e adicione as dependências oficiais. Este trecho usa Groovy e mostra apenas as partes relevantes; mantenha o restante da configuração do seu projeto.

```groovy
android {
    defaultConfig {
        minSdk 28
        ndk { abiFilters 'arm64-v8a' }
    }
    ndkVersion '27.0.12077973'
    buildFeatures { aidl true }
    externalNativeBuild {
        cmake {
            path file('src/main/cpp/CMakeLists.txt')
            version '3.22.1'
        }
    }
    packaging {
        jniLibs { useLegacyPackaging true }
    }
}

dependencies {
    implementation 'dev.rikka.shizuku:api:13.1.5'
    implementation 'dev.rikka.shizuku:provider:13.1.5'
}
```

O exemplo original compila com SDK 36 e usa minSdk/targetSdk 28. A captura depende do Android e do firmware investigados; o SDK de compilação não estabelece compatibilidade com outras versões. O snippet acima é orientação de integração, não uma matriz de versões de AGP testadas neste repositório.

A biblioteca precisa estar extraída no sistema de arquivos: `LD_PRELOAD` usa o caminho absoluto de `libcamera_stream.so`. Preserve `android:extractNativeLibs="true"` no `<application>` e o empacotamento legado das bibliotecas JNI. Apenas ter o `.so` dentro do ZIP do APK não atende a esse caminho.

## Manifesto e autorização

Declare a permissão e o provider Shizuku. Se o seu app já os configura, use a configuração existente, sem duplicar o provider.

```xml
<uses-permission android:name="moe.shizuku.manager.permission.API_V23" />
<uses-feature android:glEsVersion="0x00020000" android:required="true" />

<!-- Dentro de application, com android:extractNativeLibs="true": -->
<meta-data android:name="moe.shizuku.client.V3_SUPPORT" android:value="true" />
<provider
    android:name="rikka.shizuku.ShizukuProvider"
    android:authorities="${applicationId}.shizuku"
    android:multiprocess="false"
    android:exported="true"
    android:permission="android.permission.INTERACT_ACROSS_USERS_FULL" />
<activity
    android:name="br.com.redesurftank.havalcamera.CameraActivity"
    android:exported="false"
    android:screenOrientation="landscape"
    android:configChanges="orientation|screenSize" />
```

Abra essa Activity de dentro do seu app com um Intent explícito. `CameraService` é um Shizuku UserService, não um `android.app.Service`; ele não precisa de uma declaração `<service>` no manifesto. O fluxo solicita autorização ao usuário e exige que o Shizuku esteja rodando como root.

Se seu build usa R8, preserve os nomes das classes que o Shizuku instancia e o provider:

```proguard
-keep class br.com.redesurftank.havalcamera.CameraService { *; }
-keep class rikka.shizuku.ShizukuProvider { *; }
```

Adapte o primeiro nome se mudar o pacote Java. A [documentação oficial da Shizuku API](https://github.com/RikkaApps/Shizuku-API#userservice) descreve o contrato de instanciação e encerramento do UserService.

## Adaptar o applicationId sem remover validação

`CameraService.start()` atualmente aceita apenas:

```text
/data/app/br.com.redesurftank.havalcamera-<sufixo>/lib/arm64/libcamera_stream.so
/data/data/br.com.redesurftank.havalcamera/files/input4.xml
```

Ao incorporar em `com.exemplo.meuapp`, ajuste as duas ocorrências de `br.com.redesurftank.havalcamera` nessas validações para o ID do seu APK, incluindo eventuais sufixos de variantes. Continue comparando os caminhos canônicos e os nomes exatos dos arquivos.

Essa forma de caminho corresponde ao dispositivo Android 9 investigado. Outros Androids podem usar diretórios diferentes. Nesse caso, confira os caminhos reais e derive os diretórios esperados dos metadados do pacote instalado; não transforme essa validação em uma aceitação genérica de qualquer caminho recebido por Binder. O serviço executa código como root.

A Activity já obtém a biblioteca por `getApplicationInfo().nativeLibraryDir` e o XML por `getFilesDir()`. Se os caminhos canônicos não corresponderem à validação do serviço, corrija a integração antes de iniciar a captura.

## Consumir os quadros na sua interface

`ICameraStream.start(library, config)` devolve um `ParcelFileDescriptor` de leitura. O contrato atual não tem cabeçalho: leia exatamente **3.801.600 bytes por quadro**, em BGR24, 1760 × 720. Use uma thread de leitura, como em `CameraActivity.readFrames()`, e mantenha a leitura fora da thread de UI.

Você pode usar `CameraPreview.publish()` como o exemplo ou encaminhar o buffer completo para seu próprio renderer. Faça a troca BGR/RGB uma única vez. Se precisar recortar uma das vistas, isso recorta a composição recebida; não abre uma lente independente.

Preserve a parada no ciclo de vida, o fechamento do descritor e `unbindUserService(..., true)`. O método `destroy() = 16777114` do AIDL faz parte do encerramento esperado pelo Shizuku. Mantenha erros visíveis e apague a imagem quando o stream parar.

Finalize a integração verificando no hardware a imagem física, quadros novos, ausência de arquivos raw, fechamento do filho e reabertura. A disputa entre dois apps usando a câmera ao mesmo tempo ainda não foi validada.
