# Câmera Haval

Exemplo Android que mostra o vídeo das câmeras do Haval dentro de um APK. A captura usa o cliente Qualcomm AIS/QCarCam presente na central e um serviço com root iniciado pelo Shizuku.

[Baixar o APK de teste](https://github.com/paulovitin/haval-camera/releases) · [Integrar em outro app](docs/INTEGRATION.md) · [Funcionamento e diagnóstico](docs/CAMERA.md)

## O que funciona

Confirmamos na central testada a imagem composta de 1760 × 720: visão 360° à esquerda e câmera traseira à direita, com cores corretas e aproximadamente 15 quadros por segundo. O desenho do carro e as linhas de orientação já fazem parte desse fluxo. A implementação ainda não oferece cada lente separadamente.

O APK recebe os quadros em memória e desenha em uma `GLSurfaceView`. Durante a verificação, a pasta de dumps da câmera permaneceu vazia. O usuário confirmou na tela física os controles do APK junto da imagem.

Este primeiro release é experimental e específico para o firmware investigado. Consulte o [registro de validação](docs/VALIDATION.md) antes de tratar outra central como compatível. Ainda falta concluir os testes de fechar/reabrir, reinício e convivência com outros clientes de câmera.

## Usar o APK

Você precisa de uma central Android ARM64 com o cliente `/vendor/bin/qcarcam_test`, a entrada QCarCam de ID 4 descrita neste projeto e o **Shizuku iniciado como root (UID 0)**. Instalar o Shizuku sozinho não concede root; esta versão do app recusa o modo shell/ADB (UID 2000).

1. Instale o APK da página de releases pelo método aceito pela sua central.
2. Inicie o Shizuku com root, abra **Câmera Haval** e conceda a autorização solicitada.
3. Mantenha o app aberto para acompanhar o vídeo. **Conectar** tenta reiniciar a captura; **Fechar** encerra a Activity.

O código pede o encerramento da captura quando a Activity sai do primeiro plano. Não há inicialização automática no boot nem gravação de vídeo. Telnet, ADB e um computador foram ferramentas de investigação; o APK instalado não usa esses canais para transportar os quadros.

Na galeria, **Compartilhar na rede local** usa somente o primeiro IPv4 privado da rede Android ativa com transporte Wi-Fi, consultada por `ConnectivityManager` e `LinkProperties`. Não depende do nome da interface e não procura endereços em outras redes. O status mostra Wi-Fi, endereço, porta, código de pareamento e tempo restante. Sem Wi-Fi ativo ou IPv4 privado, mostra o motivo e não abre o portal. As permissões normais `ACCESS_NETWORK_STATE` e `INTERNET` permitem consultar a rede e abrir o socket local; o servidor continua temporário, somente leitura, pareado e ligado exatamente ao endereço selecionado. A validação física com central e telefone na mesma rede permanece pendente em [docs/VALIDATION.md](docs/VALIDATION.md).

## Compilar

**Não execute builds Android, Gradle, Kotlin ou Xcode no coordenador.** Use um commit Git imutável e auditado no Mac Mini autorizado. Não envie arquivos não commitados, credenciais, keystores, caches ou logs; não use build local como fallback. O CI existente comprova somente o baseline do build manual anterior, não esta migração Gradle nem a câmera física.

O wrapper foi regenerado no Mac Mini autorizado com Gradle 8.14.3; o JAR manteve o SHA-256 oficial `7d3a4ac4de1c32b59bc6a4eb8ecb8e612ccd0cf1ae1e99f66902da64df296172`. `gradle/verification-metadata.xml` cobre o grafo resolvido por testes, debug e release, incluindo os quatro AARs Shizuku já auditados. A verificação final executou teste unitário, debug, release, assinatura, alinhamento e integridade do ZIP no Mini. Não desative a verificação e não gere checksums automaticamente no CI.

No Mac Mini, use JDK 17 para executar Gradle 8.14.3/AGP 8.12.1; o código Java é compilado para Java 11. Instale os componentes fixados:

```sh
sdkmanager 'platforms;android-36' 'build-tools;36.0.0' 'ndk;27.0.12077973' 'cmake;3.22.1'
export ANDROID_HOME=/caminho/para/android-sdk
```

O projeto também aceita `ANDROID_SDK_ROOT`; no macOS, o script usa `$HOME/Library/Android/sdk` como padrão. Compile SDK 36, min/target SDK 28 e ABI `arm64-v8a` permanecem fixos. Como o APK é específico para instalação direta na central e não para o Google Play, somente o lint `ExpiredTargetSdkVersion` fica desativado; os demais continuam ativos. Java, AIDL e `app/src/main/cpp/CMakeLists.txt` mantêm os diretórios existentes; JNI usa extração legada para o `LD_PRELOAD`.

Ao alterar wrapper ou dependências, regenere os metadados **apenas no Mac Mini autorizado** com o Gradle 8.14.3 confiável. Os comandos de geração não são evidência de um build verificado:

```sh
./gradlew --no-daemon --write-verification-metadata sha256 wrapper --gradle-version 8.14.3 --distribution-type bin --gradle-distribution-sha256-sum bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531
./gradlew --no-daemon --write-verification-metadata sha256 :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease
```

Revise o diff do wrapper e cada novo checksum contra a origem oficial; não aceite alterações nos quatro checksums Shizuku existentes. Commit os metadados completos e o wrapper antes da verificação final em um novo snapshot auditado. O catálogo de versões fica em `gradle/libs.versions.toml`. A verificação inclui POMs/metadados e artefatos, sem exceções globais.

Depois de qualquer mudança, execute no Mac Mini:

```sh
./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug
bash build.sh
```

O teste `BuildSmokeTest.existingFrameSizeIsStable()` fixa 1760 × 720 e 3.801.600 bytes por quadro. `build.sh` monta `:app:assembleRelease` com verificação estrita, copia o APK para `build/haval-camera.apk` e executa `apksigner verify`, confere o SHA-256 do certificado EcoTrip aprovado, executa `zipalign -c 4` e `unzip -tq`. Gradle incorpora AGPL-3.0, MIT do Shizuku e avisos Apache-2.0 das novas dependências nos assets.

Builds debug usam somente a assinatura debug do Gradle. `build.sh` exige **todas** as variáveis `CAMERA_KEYSTORE`, `CAMERA_KEY_ALIAS`, `CAMERA_STORE_PASS` e `CAMERA_KEY_PASS`, e aceita o APK somente quando o certificado do assinante tem SHA-256 `5efec0f94d84d90e769635a81d51e2f25953d5d5b3cea950a115705d3b5e83e0`. Configuração parcial, keystore inexistente ou outro certificado causa erro sem imprimir senhas. Nunca publique o keystore. A fila autorizada injeta esse material somente no caminho restrito `android-camera-verify`, sem incluí-lo no arquivo-fonte ou nos artefatos.

O workflow hospedado mantém a action de checkout fixada por SHA e executa somente verificações estáticas de shell, TOML e XML. Ele não instala SDK, não executa Gradle ou `build.sh` e não publica APK. Testes, builds e artefatos Android existem somente na fila auditada do Mac Mini. Esses resultados não validam a câmera do carro nem substituem os gates físicos pendentes.

## Usar em outros aplicativos

O repositório oferece código-fonte e um app de referência. Siga o [guia de integração](docs/INTEGRATION.md) para copiar o serviço Shizuku, o AIDL, a biblioteca nativa e a renderização para seu projeto.

O app não publica um serviço de câmera aberto para outros pacotes, nem um endpoint de vídeo. Cada integração incorpora a captura, pede sua própria autorização no Shizuku e adapta as validações de caminhos ao seu `applicationId`. Isso mantém as operações root restritas aos arquivos esperados do app.

## Licença e dependências

O projeto mantém a [AGPL-3.0](LICENSE) escolhida para este repositório. Considere essa licença ao incorporar ou distribuir o código em outro aplicativo. A [Shizuku API](https://github.com/RikkaApps/Shizuku-API) usa a [licença MIT](LICENSES/Shizuku-API.txt).

O APK não inclui os binários Qualcomm nem bibliotecas extraídas da central. Ele usa os componentes que já existem no dispositivo. Este é um projeto independente, sem vínculo com GWM ou Qualcomm.
