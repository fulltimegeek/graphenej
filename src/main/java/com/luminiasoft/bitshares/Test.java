package com.luminiasoft.bitshares;

import com.google.common.primitives.UnsignedLong;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.reflect.TypeToken;
import com.luminiasoft.bitshares.errors.MalformedTransactionException;
import com.luminiasoft.bitshares.interfaces.WitnessResponseListener;
import com.luminiasoft.bitshares.models.*;
import com.luminiasoft.bitshares.test.NaiveSSLContext;
import com.luminiasoft.bitshares.ws.*;
import com.neovisionaries.ws.client.*;
import org.bitcoinj.core.*;
import org.spongycastle.crypto.Digest;
import org.spongycastle.crypto.digests.RIPEMD160Digest;
import org.spongycastle.crypto.digests.SHA512Digest;
import org.spongycastle.crypto.prng.DigestRandomGenerator;

import javax.net.ssl.SSLContext;
import java.io.*;
import java.lang.reflect.Type;
import java.math.BigInteger;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Created by nelson on 11/9/16.
 */
public class Test {

    public static final String WITNESS_URL = "ws://api.devling.xyz:8088";
    public static final String OPENLEDGER_WITNESS_URL = "wss://bitshares.openledger.info/ws";
//    public static final String WITNESS_URL = "wss://fr.blockpay.ch:8089";

    private Transaction transaction;

    public Transaction getTransaction() {
        return transaction;
    }

    private WitnessResponseListener mListener = new WitnessResponseListener() {

        @Override
        public void onSuccess(WitnessResponse response) {

            if (response.result.getClass() == AccountProperties.class) {
                AccountProperties accountProperties = (AccountProperties) response.result;
                System.out.println("Got account properties");
                System.out.println("id: " + accountProperties.id);
            } else if (response.result.getClass() == ArrayList.class) {
                List list = (List) response.result;
                if (list.size() > 0) {
                    if (list.get(0) instanceof AccountProperties) {
                        List<AccountProperties> accountPropertiesList = list;
                        for (AccountProperties accountProperties : accountPropertiesList) {
                            System.out.println("Account id: " + accountProperties.id);
                        }
                    } else if (list.get(0) instanceof AssetAmount) {
                        AssetAmount assetAmount = (AssetAmount) list.get(0);
                        System.out.println("Got fee");
                        System.out.println("amount: " + assetAmount.getAmount() + ", asset id: " + assetAmount.getAsset().getObjectId());
                    } else if (list.get(0).getClass() == ArrayList.class) {
                        List sl = (List) list.get(0);
                        if (sl.size() > 0) {
                            if (response.result.getClass() == AccountProperties.class) {
                                AccountProperties accountProperties = (AccountProperties) response.result;
                                System.out.println("Got account properties " + accountProperties);
                            } else {
                                String accountId = (String) sl.get(0);
                                System.out.println("account id : " + accountId);
                                try {

                                    // Create a custom SSL context.
                                    SSLContext context = null;
                                    context = NaiveSSLContext.getInstance("TLS");
                                    WebSocketFactory factory = new WebSocketFactory();

                                    // Set the custom SSL context.
                                    factory.setSSLContext(context);

                                    WebSocket mWebSocket = factory.createSocket(OPENLEDGER_WITNESS_URL);
                                    mWebSocket.addListener(new GetAccountNameById(accountId, null));
                                    mWebSocket.connect();
                                } catch (IOException e) {
                                    System.out.println("IOException. Msg: " + e.getMessage());
                                } catch (WebSocketException e) {
                                    System.out.println("WebSocketException. Msg: " + e.getMessage());
                                } catch (NoSuchAlgorithmException ex) {
                                    Logger.getLogger(Test.class.getName()).log(Level.SEVERE, null, ex);
                                }
                            }
                        }
                    }
                } else {
                    System.out.println("Got empty list!");
                }
            } else if (response.result.getClass() == JsonArray.class) {
                System.out.println("Json array : " + ((JsonArray)response.result));
            } else {
                System.out.println("Got other: " + response.result.getClass());
            }
        }

        @Override
        public void onError(BaseResponse.Error error) {
            System.out.println("onError. message: " + error.message);
        }
    };

    public ECKey.ECDSASignature testSigning() {
        byte[] serializedTransaction = this.transaction.toBytes();
        Sha256Hash hash = Sha256Hash.wrap(Sha256Hash.hash(serializedTransaction));
        byte[] bytesDigest = hash.getBytes();
        ECKey sk = transaction.getPrivateKey();
        ECKey.ECDSASignature signature = sk.sign(hash);
        return signature;
    }

    public String testSigningMessage() {
        byte[] serializedTransaction = this.transaction.toBytes();
        Sha256Hash hash = Sha256Hash.wrap(Sha256Hash.hash(serializedTransaction));
        ECKey sk = transaction.getPrivateKey();
        return sk.signMessage(hash.toString());
    }

    public byte[] signMessage() {
        byte[] serializedTransaction = this.transaction.toBytes();
        Sha256Hash hash = Sha256Hash.wrap(Sha256Hash.hash(serializedTransaction));
        System.out.println(">> digest <<");
        System.out.println(Util.bytesToHex(hash.getBytes()));
        ECKey sk = transaction.getPrivateKey();
        System.out.println("Private key bytes");
        System.out.println(Util.bytesToHex(sk.getPrivKeyBytes()));
        boolean isCanonical = false;
        int recId = -1;
        ECKey.ECDSASignature sig = null;
        while (!isCanonical) {
            sig = sk.sign(hash);
            if (!sig.isCanonical()) {
                System.out.println("Signature was not canonical, retrying");
                continue;
            } else {
                System.out.println("Signature is canonical");
                isCanonical = true;
            }
            // Now we have to work backwards to figure out the recId needed to recover the signature.
            for (int i = 0; i < 4; i++) {
                ECKey k = ECKey.recoverFromSignature(i, sig, hash, sk.isCompressed());
                if (k != null && k.getPubKeyPoint().equals(sk.getPubKeyPoint())) {
                    recId = i;
                    break;
                } else {
                    if (k == null) {
                        System.out.println("Recovered key was null");
                    }
                    if (k.getPubKeyPoint().equals(sk.getPubKeyPoint())) {
                        System.out.println("Recovered pub point is not equal to sk pub point");
                    }
                }
            }
            if (recId == -1) {
                throw new RuntimeException("Could not construct a recoverable key. This should never happen.");
            }
        }
        int headerByte = recId + 27 + (sk.isCompressed() ? 4 : 0);
        byte[] sigData = new byte[65];  // 1 header + 32 bytes for R + 32 bytes for S
        sigData[0] = (byte) headerByte;
        System.arraycopy(Utils.bigIntegerToBytes(sig.r, 32), 0, sigData, 1, 32);
        System.arraycopy(Utils.bigIntegerToBytes(sig.s, 32), 0, sigData, 33, 32);
        System.out.println("recId: " + recId);
        System.out.println("r: " + Util.bytesToHex(sig.r.toByteArray()));
        System.out.println("s: " + Util.bytesToHex(sig.s.toByteArray()));
        return sigData;
//        return new String(Base64.encode(sigData), Charset.forName("UTF-8"));
    }

    public void testTransactionSerialization(long head_block_number, String head_block_id, long relative_expiration) {
        BlockData blockData = new BlockData(head_block_number, head_block_id, relative_expiration);

        ArrayList<BaseOperation> operations = new ArrayList<BaseOperation>();
        UserAccount from = new UserAccount("1.2.138632");
        UserAccount to = new UserAccount("1.2.129848");
        AssetAmount amount = new AssetAmount(UnsignedLong.valueOf(100), new Asset("1.3.120"));
        AssetAmount fee = new AssetAmount(UnsignedLong.valueOf(264174), new Asset("1.3.0"));
        operations.add(new Transfer(from, to, amount, fee));
        this.transaction = new Transaction(Main.WIF, blockData, operations);
        byte[] serializedTransaction = this.transaction.toBytes();
        System.out.println("Serialized transaction");
        System.out.println(Util.bytesToHex(serializedTransaction));
    }

    public void testWebSocketTransfer() throws IOException {
        String login = "{\"id\":%d,\"method\":\"call\",\"params\":[1,\"login\",[\"\",\"\"]]}";
        String getDatabaseId = "{\"method\": \"call\", \"params\": [1, \"database\", []], \"jsonrpc\": \"2.0\", \"id\": %d}";
        String getHistoryId = "{\"method\": \"call\", \"params\": [1, \"history\", []], \"jsonrpc\": \"2.0\", \"id\": %d}";
        String getNetworkBroadcastId = "{\"method\": \"call\", \"params\": [1, \"network_broadcast\", []], \"jsonrpc\": \"2.0\", \"id\": %d}";
        String getDynamicParameters = "{\"method\": \"call\", \"params\": [0, \"get_dynamic_global_properties\", []], \"jsonrpc\": \"2.0\", \"id\": %d}";
        String rawPayload = "{\"method\": \"call\", \"params\": [%d, \"broadcast_transaction\", [{\"expiration\": \"%s\", \"signatures\": [\"%s\"], \"operations\": [[0, {\"fee\": {\"amount\": 264174, \"asset_id\": \"1.3.0\"}, \"amount\": {\"amount\": 100, \"asset_id\": \"1.3.120\"}, \"to\": \"1.2.129848\", \"extensions\": [], \"from\": \"1.2.138632\"}]], \"ref_block_num\": %d, \"extensions\": [], \"ref_block_prefix\": %d}]], \"jsonrpc\": \"2.0\", \"id\": %d}";

//        String url = "wss://bitshares.openledger.info/ws";
        String url = "ws://api.devling.xyz:8088";
        WebSocketFactory factory = new WebSocketFactory().setConnectionTimeout(5000);

        // Create a WebSocket. The timeout value set above is used.
        WebSocket ws = factory.createSocket(url);

        ws.addListener(new WebSocketAdapter() {

            private DynamicGlobalProperties dynProperties;
            private int networkBroadcastApiId;

            @Override
            public void onConnected(WebSocket websocket, Map<String, List<String>> headers) throws Exception {
                System.out.println("onConnected");
                String payload = String.format(login, 1);
                System.out.println(">>");
                System.out.println(payload);
                websocket.sendText(payload);
            }

            @Override
            public void onDisconnected(WebSocket websocket, WebSocketFrame serverCloseFrame, WebSocketFrame clientCloseFrame, boolean closedByServer) throws Exception {
                System.out.println("onDisconnected");
            }

            @Override
            public void onTextFrame(WebSocket websocket, WebSocketFrame frame) throws Exception {
                System.out.println("<<");
                String response = frame.getPayloadText();
                System.out.println(response);
                Gson gson = new Gson();
                BaseResponse baseResponse = gson.fromJson(response, BaseResponse.class);
//                if(baseResponse.id.equals("1")){
//                    String payload = String.format(getDatabaseId, 2);
//                    System.out.println(">>");
//                    System.out.println(payload);
//                    websocket.sendText(payload);
//                }else if(baseResponse.id.equals("2")){
//                    String payload = String.format(getHistoryId, 3);
//                    System.out.println(">>");
//                    System.out.println(payload);
//                    websocket.sendText(payload);
//                }else if(baseResponse.id.equals("3")){
                if (baseResponse.id == 1) {
                    String payload = String.format(getNetworkBroadcastId, 2);
                    System.out.println(">>");
                    System.out.println(payload);
                    websocket.sendText(payload);
//                }else if(baseResponse.id.equals("4")){
                }
                if (baseResponse.id == 2) {
                    String payload = String.format(getDynamicParameters, 3);
                    Type ApiIdResponse = new TypeToken<WitnessResponse<Integer>>() {
                    }.getType();
                    WitnessResponse<Integer> witnessResponse = gson.fromJson(response, ApiIdResponse);
                    networkBroadcastApiId = witnessResponse.result.intValue();
                    System.out.println(">>");
                    System.out.println(payload);
                    websocket.sendText(payload);
                } else if (baseResponse.id == 3) {
                    // Got dynamic properties
                    Type DynamicGlobalPropertiesResponse = new TypeToken<WitnessResponse<DynamicGlobalProperties>>() {
                    }.getType();
                    WitnessResponse<DynamicGlobalProperties> witnessResponse = gson.fromJson(response, DynamicGlobalPropertiesResponse);
                    dynProperties = witnessResponse.result;

                    SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
                    dateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
                    Date date = dateFormat.parse(dynProperties.time);
                    long expirationTime = (date.getTime() / 1000) + 30;
                    testTransactionSerialization(dynProperties.head_block_number, dynProperties.head_block_id, expirationTime);

                    BlockData blockData = new BlockData(dynProperties.head_block_number, dynProperties.head_block_id, expirationTime);
                    byte[] signatureBytes = signMessage();

                    String payload = String.format(
                            rawPayload,
                            networkBroadcastApiId,
                            dateFormat.format(new Date(expirationTime * 1000)),
                            Util.bytesToHex(signatureBytes),
                            blockData.getRefBlockNum(),
                            blockData.getRefBlockPrefix(),
                            4);
                    System.out.println(">>");
                    System.out.println(payload);
                    websocket.sendText(payload);
                }
            }

            @Override
            public void onError(WebSocket websocket, WebSocketException cause) throws Exception {
                System.out.println("onError");
            }

            @Override
            public void onUnexpectedError(WebSocket websocket, WebSocketException cause) throws Exception {
                System.out.println("onUnexpectedError");
            }

            @Override
            public void handleCallbackError(WebSocket websocket, Throwable cause) throws Exception {
                System.out.println("handleCallbackError. Msg: " + cause.getMessage());
                StackTraceElement[] stackTrace = cause.getStackTrace();
                for (StackTraceElement line : stackTrace) {
                    System.out.println(line.toString());
                }
            }
        });
        try {
            // Connect to the server and perform an opening handshake.
            // This method blocks until the opening handshake is finished.
            ws.connect();
        } catch (OpeningHandshakeException e) {
            // A violation against the WebSocket protocol was detected
            // during the opening handshake.
            System.out.println("OpeningHandshakeException");
        } catch (WebSocketException e) {
            // Failed to establish a WebSocket connection.
            System.out.println("WebSocketException. Msg: " + e.getMessage());
        }
    }

    public void testCustomSerializer() {
        AssetAmount amount = new AssetAmount(UnsignedLong.valueOf(100), new Asset("1.3.120"));
        String jsonAmount = amount.toJsonString();
        System.out.println("JSON amount");
        System.out.println(jsonAmount);
    }

    public void testUserAccountSerialization() {
        UserAccount account = new UserAccount("1.2.138632");
        System.out.println(Util.bytesToHex(account.toBytes()));
    }

    public void testTransactionSerialization() {
        try {
            Transaction transaction = new TransferTransactionBuilder()
                    .setSource(new UserAccount("1.2.138632"))
                    .setDestination(new UserAccount("1.2.129848"))
                    .setAmount(new AssetAmount(UnsignedLong.valueOf(100), new Asset("1.3.120")))
                    .setFee(new AssetAmount(UnsignedLong.valueOf(264174), new Asset("1.3.0")))
                    .setBlockData(new BlockData(Main.REF_BLOCK_NUM, Main.REF_BLOCK_PREFIX, Main.RELATIVE_EXPIRATION))
                    .setPrivateKey(DumpedPrivateKey.fromBase58(null, Main.WIF).getKey())
                    .build();

            ArrayList<Serializable> transactionList = new ArrayList<>();
            transactionList.add(transaction);

            byte[] signature = transaction.getGrapheneSignature();
            System.out.println(Util.bytesToHex(signature));
            ApiCall call = new ApiCall(4, "call", "broadcast_transaction", transactionList, "2.0", 1);
            String jsonCall = call.toJsonString();
            System.out.println("json call");
            System.out.println(jsonCall);
        } catch (MalformedTransactionException e) {
            System.out.println("MalformedTransactionException. Msg: " + e.getMessage());
        }
    }

    public void testLoginSerialization() {
        ArrayList<Serializable> loginParams = new ArrayList<>();
//        loginParams.add("nelson");
//        loginParams.add("supersecret");
        loginParams.add(null);
        loginParams.add(null);
        ApiCall loginCall = new ApiCall(1, "login", loginParams, "2.0", 1);
        String jsonLoginCall = loginCall.toJsonString();
        System.out.println("login call");
        System.out.println(jsonLoginCall);
    }

    public void testNetworkBroadcastSerialization() {
        ArrayList<Serializable> params = new ArrayList<>();
        ApiCall networkParamsCall = new ApiCall(3, "network_broadcast", params, "2.0", 1);
        String call = networkParamsCall.toJsonString();
        System.out.println("network broadcast");
        System.out.println(call);
    }

    public void testNetworkBroadcastDeserialization() {
        String response = "{\"id\":2,\"result\":2}";
        Gson gson = new Gson();
        Type ApiIdResponse = new TypeToken<WitnessResponse<Integer>>() {
        }.getType();
        WitnessResponse<Integer> witnessResponse = gson.fromJson(response, ApiIdResponse);
    }

    public void testGetDynamicParams() {
        ArrayList<Serializable> emptyParams = new ArrayList<>();
        ApiCall getDynamicParametersCall = new ApiCall(0, "get_dynamic_global_properties", emptyParams, "2.0", 0);
        System.out.println(getDynamicParametersCall.toJsonString());
    }

    public void testRequiredFeesResponse() {
        String response = "{\"id\":1,\"result\":[{\"amount\":264174,\"asset_id\":\"1.3.0\"}]}";
        Type AccountLookupResponse = new TypeToken<WitnessResponse<List<AssetAmount>>>() {
        }.getType();
        GsonBuilder gsonBuilder = new GsonBuilder();
        gsonBuilder.registerTypeAdapter(AssetAmount.class, new AssetAmount.AssetDeserializer());
        WitnessResponse<List<AssetAmount>> witnessResponse = gsonBuilder.create().fromJson(response, AccountLookupResponse);
        for (AssetAmount assetAmount : witnessResponse.result) {
            System.out.println("asset : " + assetAmount.toJsonString());
        }
    }

    public void testTransactionBroadcastSequence() {
        String url = Test.OPENLEDGER_WITNESS_URL;
        WitnessResponseListener listener = new WitnessResponseListener() {
            @Override
            public void onSuccess(WitnessResponse response) {
                System.out.println("onSuccess");
            }

            @Override
            public void onError(BaseResponse.Error error) {
                System.out.println("onError");
                System.out.println(error.data.message);
            }
        };

        try {
            Transaction transaction = new TransferTransactionBuilder()
                    .setSource(new UserAccount("1.2.138632"))
                    .setDestination(new UserAccount("1.2.129848"))
                    .setAmount(new AssetAmount(UnsignedLong.valueOf(100), new Asset("1.3.120")))
                    .setFee(new AssetAmount(UnsignedLong.valueOf(264174), new Asset("1.3.0")))
                    .setBlockData(new BlockData(43408, 1430521623, 1479231969))
                    .setPrivateKey(DumpedPrivateKey.fromBase58(null, Main.WIF).getKey())
                    .build();

            ArrayList<Serializable> transactionList = new ArrayList<>();
            transactionList.add(transaction);

            transactionList.add(transaction);

            SSLContext context = null;
            context = NaiveSSLContext.getInstance("TLS");
            WebSocketFactory factory = new WebSocketFactory();

            // Set the custom SSL context.
            factory.setSSLContext(context);

            WebSocket mWebSocket = factory.createSocket(OPENLEDGER_WITNESS_URL);

            mWebSocket.addListener(new TransactionBroadcastSequence(transaction, listener));
            mWebSocket.connect();

        } catch (MalformedTransactionException e) {
            System.out.println("MalformedTransactionException. Msg: " + e.getMessage());
        } catch (IOException e) {
            System.out.println("IOException. Msg: " + e.getMessage());
        } catch (WebSocketException e) {
            System.out.println("WebSocketException. Msg: " + e.getMessage());
        } catch (NoSuchAlgorithmException e) {
            System.out.println("NoSuchAlgoritmException. Msg: " + e.getMessage());
        }
    }

    public void testAccountLookupDeserialization() {
        String response = "{\"id\":1,\"result\":[[\"ken\",\"1.2.3111\"],[\"ken-1\",\"1.2.101491\"],[\"ken-k\",\"1.2.108646\"]]}";
        Type AccountLookupResponse = new TypeToken<WitnessResponse<List<List<String>>>>() {
        }.getType();
        Gson gson = new Gson();
        WitnessResponse<List<List<String>>> witnessResponse = gson.fromJson(response, AccountLookupResponse);
        for (int i = 0; i < witnessResponse.result.size(); i++) {
            System.out.println("suggested name: " + witnessResponse.result.get(i).get(0));
        }
    }

    public void testPrivateKeyManipulations() {
        ECKey privateKey = DumpedPrivateKey.fromBase58(null, Main.WIF).getKey();
        System.out.println("private key..............: " + Util.bytesToHex(privateKey.getSecretBytes()));
        System.out.println("public key uncompressed..: " + Util.bytesToHex(privateKey.getPubKey()));
        System.out.println("public key compressed....: " + Util.bytesToHex(privateKey.getPubKeyPoint().getEncoded(true)));
        System.out.println("base58...................: " + Base58.encode(privateKey.getPubKeyPoint().getEncoded(true)));
        System.out.println("base58...................: " + Base58.encode(privateKey.getPubKey()));
        String brainKeyWords = "PUMPER ISOTOME SERE STAINER CLINGER MOONLIT CHAETA UPBRIM AEDILIC BERTHER NIT SHAP SAID SHADING JUNCOUS CHOUGH";
        BrainKey brainKey = new BrainKey(brainKeyWords, 0);
    }

    public void testGetAccountByName() {
        try {
            WebSocketFactory factory = new WebSocketFactory().setConnectionTimeout(5000);
            WebSocket mWebSocket = factory.createSocket(WITNESS_URL);
            mWebSocket.addListener(new GetAccountByName("bilthon-83", mListener));
            mWebSocket.connect();
        } catch (IOException e) {
            System.out.println("IOException. Msg: " + e.getMessage());
        } catch (WebSocketException e) {
            System.out.println("WebSocketException. Msg: " + e.getMessage());
        }
    }

    public void testGetRequiredFees() {
        ArrayList<Serializable> accountParams = new ArrayList<>();
        Asset asset = new Asset("1.3.0");
        UserAccount from = new UserAccount("1.2.138632");
        UserAccount to = new UserAccount("1.2.129848");
        AssetAmount amount = new AssetAmount(UnsignedLong.valueOf(100), new Asset("1.3.120"));
        AssetAmount fee = new AssetAmount(UnsignedLong.valueOf(264174), new Asset("1.3.0"));
        Transfer transfer = new Transfer(from, to, amount, fee);
        ArrayList<BaseOperation> operations = new ArrayList<>();
        operations.add(transfer);

        accountParams.add(operations);
        accountParams.add(asset.getObjectId());

        try {
            WebSocketFactory factory = new WebSocketFactory().setConnectionTimeout(5000);
            WebSocket mWebSocket = factory.createSocket(WITNESS_URL);
            mWebSocket.addListener(new GetRequiredFees(operations, asset, mListener));
            mWebSocket.connect();
        } catch (IOException e) {
            System.out.println("IOException. Msg: " + e.getMessage());
        } catch (WebSocketException e) {
            System.out.println("WebSocketException. Msg: " + e.getMessage());
        }
    }

    public void testRandomNumberGeneration() {
        byte[] seed = new byte[]{new Long(System.nanoTime()).byteValue()};
        doCountTest(new SHA512Digest(), seed);
    }

    private void doCountTest(Digest digest, byte[] seed)//, byte[] expectedXors)
    {
        DigestRandomGenerator generator = new DigestRandomGenerator(digest);
        byte[] output = new byte[digest.getDigestSize()];
        int[] averages = new int[digest.getDigestSize()];
        byte[] ands = new byte[digest.getDigestSize()];
        byte[] xors = new byte[digest.getDigestSize()];
        byte[] ors = new byte[digest.getDigestSize()];

        generator.addSeedMaterial(seed);

        for (int i = 0; i != 1000000; i++) {
            generator.nextBytes(output);
            for (int j = 0; j != output.length; j++) {
                averages[j] += output[j] & 0xff;
                ands[j] &= output[j];
                xors[j] ^= output[j];
                ors[j] |= output[j];
            }
        }

        for (int i = 0; i != output.length; i++) {
            if ((averages[i] / 1000000) != 127) {
                System.out.println("average test failed for " + digest.getAlgorithmName());
            }
            System.out.println("averages[" + i + "] / 1000000: " + averages[i] / 1000000);
            if (ands[i] != 0) {
                System.out.println("and test failed for " + digest.getAlgorithmName());
            }
            if ((ors[i] & 0xff) != 0xff) {
                System.out.println("or test failed for " + digest.getAlgorithmName());
            }
//            if (xors[i] != expectedXors[i]) {
//                System.out.println("xor test failed for " + digest.getAlgorithmName());
//            }
        }
    }

    /**
     * The final purpose of this test is to convert the plain brainkey at
     * Main.BRAIN_KEY into the WIF at Main.WIF
     */
    public void testBrainKeyOperations(boolean random) {
        try {
            BrainKey brainKey;
            if (random) {
                String current = new java.io.File(".").getCanonicalPath();
                File file = new File(current + "/src/main/java/com/luminiasoft/bitshares/brainkeydict.txt");

                BufferedReader bufferedReader = new BufferedReader(new FileReader(file));
                StringBuffer buffer = new StringBuffer();
                String words = bufferedReader.readLine();
                String suggestion = BrainKey.suggest(words);
                brainKey = new BrainKey(suggestion, 0);
            } else {
                brainKey = new BrainKey(Main.BRAIN_KEY, 0);
            }
            ECKey key = brainKey.getPrivateKey();
            System.out.println("Private key");
            System.out.println(Util.bytesToHex(key.getSecretBytes()));
            String wif = key.getPrivateKeyAsWiF(NetworkParameters.fromID(NetworkParameters.ID_MAINNET));
            System.out.println("wif compressed: " + wif);
            String wif2 = key.decompress().getPrivateKeyAsWiF(NetworkParameters.fromID(NetworkParameters.ID_MAINNET));
            System.out.println("wif decompressed: " + wif2);

            byte[] pubKey1 = key.decompress().getPubKey();
            System.out.println("decompressed public key: " + Base58.encode(pubKey1));
            byte[] pubKey2 = key.getPubKey();
            System.out.println("compressed public key: " + Base58.encode(pubKey2));

            System.out.println("pub key compressed   : " + Util.bytesToHex(pubKey1));
            System.out.println("pub key uncompressed : " + Util.bytesToHex(pubKey2));

            byte[] pubKey3 = key.getPubKeyPoint().getEncoded(true);
            System.out.println("pub key compressed  : " + Base58.encode(pubKey3));

            // Address generation test
            Address address = new Address(key);
            System.out.println("Block explorer's address: " + address);

            System.out.println("Wif:                : " + brainKey.getWalletImportFormat());
        } catch (FileNotFoundException e) {
            System.out.println("FileNotFoundException. Msg: " + e.getMessage());
        } catch (IOException e) {
            System.out.println("IOException. Msg: " + e.getMessage());
        }
    }

    public byte[] calculateChecksum(byte[] input) {
        byte[] answer = new byte[4];
        RIPEMD160Digest ripemd160Digest = new RIPEMD160Digest();
        ripemd160Digest.update(input, 0, input.length);
        byte[] output = new byte[160 / 8];
        ripemd160Digest.doFinal(output, 0);
        System.arraycopy(output, 0, answer, 0, 4);
        return answer;
    }

    public void testBip39Opertion() {
        BIP39 bip39 = new BIP39(Main.BIP39_KEY, "");
    }

    public void testAccountNamebyAddress() {
        WitnessResponseListener listener = new WitnessResponseListener() {
            @Override
            public void onSuccess(WitnessResponse response) {
                System.out.println("onSuccess");
            }

            @Override
            public void onError(BaseResponse.Error error) {
                System.out.println("onError");
            }
        };

        BrainKey brainKey = new BrainKey(Main.BRAIN_KEY, 0);
        Address address = new Address(brainKey.getPrivateKey());
        try {
            // Create a custom SSL context.
            SSLContext context = null;
            context = NaiveSSLContext.getInstance("TLS");
            WebSocketFactory factory = new WebSocketFactory();

            // Set the custom SSL context.
            factory.setSSLContext(context);

            WebSocket mWebSocket = factory.createSocket(OPENLEDGER_WITNESS_URL);
            mWebSocket.addListener(new GetAccountsByAddress(address, listener));
            mWebSocket.connect();
        } catch (IOException e) {
            System.out.println("IOException. Msg: " + e.getMessage());
        } catch (WebSocketException e) {
            System.out.println("WebSocketException. Msg: " + e.getMessage());
        } catch (NoSuchAlgorithmException e) {
            System.out.println("NoSuchAlgorithmException. Msg: " + e.getMessage());
        }
    }

    public void testAccountNameById() {
        try {
            // Create a custom SSL context.
            SSLContext context = null;
            context = NaiveSSLContext.getInstance("TLS");
            WebSocketFactory factory = new WebSocketFactory();

            // Set the custom SSL context.
            factory.setSSLContext(context);

            WebSocket mWebSocket = factory.createSocket(OPENLEDGER_WITNESS_URL);
            mWebSocket.addListener(new GetAccountNameById("1.2.138632", mListener));
            mWebSocket.connect();
        } catch (IOException e) {
            System.out.println("IOException. Msg: " + e.getMessage());
        } catch (WebSocketException e) {
            System.out.println("WebSocketException. Msg: " + e.getMessage());
        } catch (NoSuchAlgorithmException e) {
            System.out.println("NoSuchAlgorithmException. Msg: " + e.getMessage());
        }
    }

    public void testRelativeAccountHistory() {
        GetRelativeAccountHistory relativeAccountHistory = new GetRelativeAccountHistory(new UserAccount("1.2.138632"), mListener);
        try {
            // Create a custom SSL context.
            SSLContext context = null;
            context = NaiveSSLContext.getInstance("TLS");
            WebSocketFactory factory = new WebSocketFactory();

            // Set the custom SSL context.
            factory.setSSLContext(context);

            WebSocket mWebSocket = factory.createSocket(OPENLEDGER_WITNESS_URL);
            mWebSocket.addListener(relativeAccountHistory);
            mWebSocket.connect();
        } catch (IOException e) {
            System.out.println("IOException. Msg: " + e.getMessage());
        } catch (WebSocketException e) {
            System.out.println("WebSocketException. Msg: " + e.getMessage());
        } catch (NoSuchAlgorithmException e) {
            System.out.println("NoSuchAlgorithmException. Msg: " + e.getMessage());
        }
    }

    public void testingInvoiceGeneration() {
        Invoice.LineItem[] lineItem = new Invoice.LineItem[]{new Invoice.LineItem("Apples", 2, "20 CSD")};
        Invoice invoice = new Invoice("bilthon-83", "Bilthon's store", "Invoice #12", "BTS", lineItem, "Thank you", "");
        String qrCodeData = Invoice.toQrCode(invoice);
        System.out.println("qrCodeData");
        System.out.println(qrCodeData);
        Invoice recovered = Invoice.fromQrCode(qrCodeData);
        System.out.println("recovered invoice: " + recovered.toJsonString());
    }

    public void testCompression() {
        String test = "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat. Duis aute irure dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla pariatur. Excepteur sint occaecat cupidatat non proident, sunt in culpa qui officia deserunt mollit anim id est laborum.";
        System.out.println("to compress");
        System.out.println(Util.bytesToHex(test.getBytes()));
        byte[] compressed = Util.compress(test.getBytes(), Util.XZ);
        System.out.println("compressed");
        System.out.println(Util.bytesToHex(compressed));
    }

    public void testCreateBinFile() {
        byte[] fileOutput = FileBin.getBytesFromBrainKey(Main.BRAIN_KEY, "123456", "bithon-83");
        ///String stringFile = "02f9f3eb0f61a0a96134975d86048bf92e114d6a1ce286140cad3a96c33e697282bc0a8a24d1ad0c7bc084a79816ce38e36bd2d624aa8bf686f53fb4c7e25e3974da9b40e0b17e9d0b5b82793a04b19646169c49c58cd67f4950aee7d275141dd24f52baaaee772995a9bd6a6562a7a38aae08951236d3f612aecef7aedd720a91eacbab3a792ca3ebe0105838fe11f6e9d0e83e5d77eb82f17c7ba85c670e69294a8bcf8365cfeca487a60093498496bbec394c729e3fda9f32fdccdea56288b36fb14a26aa309b548a6dd9c1d616d22167348f8d580f9dc7361b4457d2dc6d75ec985d8e2d3dcdff89cd425d9f14037ac961eb10ac5f92bab356ccecd8cf018ec05ab40d915b628a75ae32cfa4005634f08b24c0dc8c5a7636ed70cbd86a7f0c4f6236d74310470fafe3af8b5346c8cb61957f7292b468d276498f9e806399588b0afd5777e6ee5fe7cd3a6691d9b5486cb5c7adbd5ad0b17588dd32d82b01d49ecf0f2bf24ee54a490ee620e8ab049047ffa416b5efa8f1f0155d8f1be866a10d0d62ae44a3a8ecc0121c08837c2ee1a25f8b6dd7266273c41f4b9a5e3d600e3fb4de870f99ab1a7196d93f222595f92e97a2480f58b61b62639154a374b987664fd317622aaad156f831b03f2d9606537b65b3b1fcfb1fb6be39560ad2c301dd1fc25cee755e61b49ebfe42ca7e64b4b0fc4aa347b48a85c0b585a3499fe278e25cb2141f8009b9afc875fa2a2c439bf6cdec4b5190a6deb7f9390f072beb24749a8a2114cc1870c07be079abb3ee0ebc827f9b53e158a529bc6552eba280f05edf5f7ae1911de7acb4888150a509d029ec7c9da6de8adabbca6773a0a293a0a42de8278c82e88b9390b42b56f58bd8633fb97130e799a47a744e2e8958fd5";
        //fileOutput = new BigInteger(stringFile, 16).toByteArray();
        System.out.println(FileBin.getBrainkeyFromByte(fileOutput, "123456"));
    }

    public void testImportBinFile() {
        byte inputBytes[] = new byte[]{(byte) 2, (byte) 175, (byte) 24, (byte) 227, (byte) 182, (byte) 11, (byte) 113, (byte) 151, (byte) 112, (byte) 157, (byte) 137, (byte) 157, (byte) 244, (byte) 237, (byte) 228, (byte) 92, (byte) 34, (byte) 138, (byte) 171, (byte) 248, (byte) 24, (byte) 161, (byte) 171, (byte) 75, (byte) 2, (byte) 183, (byte) 47, (byte) 213, (byte) 50, (byte) 109, (byte) 220, (byte) 6, (byte) 124, (byte) 108, (byte) 32, (byte) 164, (byte) 204, (byte) 234, (byte) 10, (byte) 12, (byte) 154, (byte) 225, (byte) 11, (byte) 178, (byte) 238, (byte) 9, (byte) 122, (byte) 244, (byte) 175, (byte) 185, (byte) 143, (byte) 27, (byte) 134, (byte) 192, (byte) 37, (byte) 239, (byte) 148, (byte) 253, (byte) 124, (byte) 68, (byte) 6, (byte) 211, (byte) 20, (byte) 224, (byte) 50, (byte) 31, (byte) 208, (byte) 87, (byte) 115, (byte) 115, (byte) 11, (byte) 81, (byte) 182, (byte) 223, (byte) 230, (byte) 102, (byte) 230, (byte) 245, (byte) 182, (byte) 77, (byte) 157, (byte) 182, (byte) 79, (byte) 247, (byte) 134, (byte) 178, (byte) 87, (byte) 93, (byte) 146, (byte) 89, (byte) 167, (byte) 191, (byte) 34, (byte) 17, (byte) 117, (byte) 173, (byte) 59, (byte) 142, (byte) 54, (byte) 120, (byte) 237, (byte) 124, (byte) 217, (byte) 252, (byte) 112, (byte) 97, (byte) 153, (byte) 124, (byte) 144, (byte) 80, (byte) 33, (byte) 130, (byte) 15, (byte) 18, (byte) 157, (byte) 98, (byte) 130, (byte) 80, (byte) 206, (byte) 27, (byte) 8, (byte) 142, (byte) 245, (byte) 22, (byte) 244, (byte) 219, (byte) 38, (byte) 228, (byte) 173, (byte) 147, (byte) 42, (byte) 100, (byte) 99, (byte) 108, (byte) 146, (byte) 110, (byte) 100, (byte) 215, (byte) 183, (byte) 20, (byte) 112, (byte) 93, (byte) 195, (byte) 12, (byte) 174, (byte) 130, (byte) 35, (byte) 71, (byte) 172, (byte) 237, (byte) 112, (byte) 197, (byte) 250, (byte) 67, (byte) 36, (byte) 185, (byte) 117, (byte) 211, (byte) 147, (byte) 21, (byte) 251, (byte) 214, (byte) 178, (byte) 152, (byte) 25, (byte) 107, (byte) 206, (byte) 184, (byte) 113, (byte) 67, (byte) 169, (byte) 55, (byte) 95, (byte) 249, (byte) 193, (byte) 215, (byte) 20, (byte) 124, (byte) 62, (byte) 179, (byte) 125, (byte) 2, (byte) 96, (byte) 46, (byte) 137, (byte) 133, (byte) 46, (byte) 37, (byte) 138, (byte) 19, (byte) 215, (byte) 2, (byte) 189, (byte) 91, (byte) 61, (byte) 119, (byte) 150, (byte) 6, (byte) 188, (byte) 220, (byte) 232, (byte) 12, (byte) 108, (byte) 128, (byte) 92, (byte) 172, (byte) 119, (byte) 138, (byte) 215, (byte) 90, (byte) 8, (byte) 56, (byte) 126, (byte) 145, (byte) 133, (byte) 193, (byte) 47, (byte) 147, (byte) 106, (byte) 219, (byte) 58, (byte) 227, (byte) 20, (byte) 60, (byte) 147, (byte) 38, (byte) 218, (byte) 17, (byte) 130, (byte) 196, (byte) 134, (byte) 105, (byte) 94, (byte) 235, (byte) 26, (byte) 245, (byte) 119, (byte) 153, (byte) 11, (byte) 29, (byte) 33, (byte) 230, (byte) 151, (byte) 149, (byte) 63, (byte) 91, (byte) 170, (byte) 75, (byte) 43, (byte) 223, (byte) 192, (byte) 104, (byte) 161, (byte) 58, (byte) 135, (byte) 226, (byte) 175, (byte) 171, (byte) 202, (byte) 113, (byte) 142, (byte) 40, (byte) 139, (byte) 240, (byte) 10, (byte) 54, (byte) 213, (byte) 55, (byte) 235, (byte) 175, (byte) 211, (byte) 193, (byte) 151, (byte) 43, (byte) 233, (byte) 81, (byte) 250, (byte) 245, (byte) 120, (byte) 211, (byte) 107, (byte) 73, (byte) 75, (byte) 74, (byte) 98, (byte) 10, (byte) 208, (byte) 68, (byte) 185, (byte) 183, (byte) 251, (byte) 193, (byte) 65, (byte) 125, (byte) 65, (byte) 52, (byte) 154, (byte) 115, (byte) 118, (byte) 217, (byte) 254, (byte) 140, (byte) 116, (byte) 124, (byte) 158, (byte) 70, (byte) 94, (byte) 28, (byte) 132, (byte) 231, (byte) 142, (byte) 209, (byte) 163, (byte) 182, (byte) 227, (byte) 129, (byte) 243, (byte) 130, (byte) 28, (byte) 238, (byte) 35, (byte) 235, (byte) 120, (byte) 199, (byte) 26, (byte) 209, (byte) 58, (byte) 181, (byte) 124, (byte) 44, (byte) 38, (byte) 132, (byte) 54, (byte) 168, (byte) 31, (byte) 150, (byte) 191, (byte) 140, (byte) 101, (byte) 141, (byte) 104, (byte) 74, (byte) 29, (byte) 76, (byte) 254, (byte) 67, (byte) 43, (byte) 123, (byte) 67, (byte) 208, (byte) 132, (byte) 61, (byte) 36, (byte) 167, (byte) 195, (byte) 231, (byte) 234, (byte) 136, (byte) 55, (byte) 97, (byte) 205, (byte) 242, (byte) 182, (byte) 237, (byte) 179, (byte) 13, (byte) 24, (byte) 249, (byte) 53, (byte) 151, (byte) 66, (byte) 252, (byte) 254, (byte) 173, (byte) 91, (byte) 52, (byte) 70, (byte) 239, (byte) 235, (byte) 94, (byte) 18, (byte) 115, (byte) 143, (byte) 134, (byte) 206, (byte) 244, (byte) 77, (byte) 247, (byte) 201, (byte) 61, (byte) 115, (byte) 78, (byte) 186, (byte) 199, (byte) 89, (byte) 144, (byte) 69, (byte) 231, (byte) 174, (byte) 2, (byte) 167, (byte) 157, (byte) 148, (byte) 88, (byte) 150, (byte) 171, (byte) 50, (byte) 82, (byte) 230, (byte) 211, (byte) 14, (byte) 55, (byte) 165, (byte) 103, (byte) 67, (byte) 172, (byte) 148, (byte) 252, (byte) 10, (byte) 104, (byte) 24, (byte) 179, (byte) 152, (byte) 156, (byte) 169, (byte) 228, (byte) 123, (byte) 205, (byte) 247, (byte) 10, (byte) 127, (byte) 106, (byte) 100, (byte) 10, (byte) 187, (byte) 81, (byte) 0, (byte) 55, (byte) 177, (byte) 60, (byte) 139, (byte) 41, (byte) 62, (byte) 163, (byte) 83, (byte) 242, (byte) 1, (byte) 122, (byte) 247, (byte) 181, (byte) 102, (byte) 218, (byte) 205, (byte) 70, (byte) 235, (byte) 147, (byte) 195, (byte) 107, (byte) 248, (byte) 139, (byte) 169, (byte) 203, (byte) 174, (byte) 22, (byte) 126, (byte) 65, (byte) 123, (byte) 14, (byte) 33, (byte) 131, (byte) 49, (byte) 6, (byte) 187, (byte) 156, (byte) 50, (byte) 92, (byte) 145, (byte) 74, (byte) 90, (byte) 132, (byte) 151, (byte) 105, (byte) 187, (byte) 195, (byte) 56, (byte) 45, (byte) 134, (byte) 204, (byte) 7, (byte) 130, (byte) 153, (byte) 209, (byte) 87, (byte) 231, (byte) 78, (byte) 90, (byte) 168, (byte) 93, (byte) 200, (byte) 149, (byte) 204, (byte) 128, (byte) 85, (byte) 17, (byte) 17, (byte) 219, (byte) 161, (byte) 167, (byte) 73, (byte) 218, (byte) 116, (byte) 233, (byte) 202, (byte) 19, (byte) 110, (byte) 95, (byte) 115, (byte) 233, (byte) 137, (byte) 85, (byte) 112, (byte) 70, (byte) 226, (byte) 217, (byte) 126, (byte) 70, (byte) 214, (byte) 47, (byte) 133, (byte) 129, (byte) 78, (byte) 127, (byte) 81, (byte) 192, (byte) 48, (byte) 91, (byte) 224, (byte) 124, (byte) 13, (byte) 176, (byte) 131, (byte) 53, (byte) 192, (byte) 92, (byte) 113, (byte) 235, (byte) 86, (byte) 38, (byte) 178, (byte) 133, (byte) 204, (byte) 110, (byte) 195, (byte) 230, (byte) 140, (byte) 213, (byte) 208, (byte) 188, (byte) 185, (byte) 37, (byte) 103, (byte) 177, (byte) 181, (byte) 120, (byte) 78, (byte) 192, (byte) 30, (byte) 224, (byte) 250, (byte) 2, (byte) 66, (byte) 76, (byte) 162, (byte) 87, (byte) 8, (byte) 131, (byte) 54, (byte) 247, (byte) 91, (byte) 9, (byte) 236, (byte) 18, (byte) 53, (byte) 11, (byte) 141, (byte) 144, (byte) 193, (byte) 139, (byte) 168, (byte) 170, (byte) 223, (byte) 190, (byte) 90, (byte) 23, (byte) 29, (byte) 177, (byte) 79, (byte) 38, (byte) 232, (byte) 148, (byte) 80, (byte) 211, (byte) 207, (byte) 201, (byte) 129, (byte) 2, (byte) 228, (byte) 86, (byte) 144, (byte) 32, (byte) 27, (byte) 235, (byte) 105, (byte) 136, (byte) 217, (byte) 195, (byte) 234, (byte) 243, (byte) 198, (byte) 87, (byte) 186, (byte) 31, (byte) 21, (byte) 144, (byte) 200, (byte) 27, (byte) 34, (byte) 82, (byte) 220, (byte) 37, (byte) 67, (byte) 44, (byte) 140, (byte) 233, (byte) 144, (byte) 218, (byte) 185, (byte) 46, (byte) 151, (byte) 96, (byte) 91};

        System.out.println(FileBin.getBrainkeyFromByte(inputBytes, "123456"));

    }

    void testLookout() {
        try {
            GetAccountIdByName query = new GetAccountIdByName("henrutest-125", mListener);
            SSLContext context = null;
            context = NaiveSSLContext.getInstance("TLS");
            WebSocketFactory factory = new WebSocketFactory();

            // Set the custom SSL context.
            factory.setSSLContext(context);

            WebSocket mWebSocket = factory.createSocket(OPENLEDGER_WITNESS_URL);
            mWebSocket.addListener(query);
            mWebSocket.connect();
        } catch (WebSocketException ex) {
            Logger.getLogger(Test.class.getName()).log(Level.SEVERE, null, ex);
        } catch (IOException ex) {
            Logger.getLogger(Test.class.getName()).log(Level.SEVERE, null, ex);
        } catch (NoSuchAlgorithmException ex) {
            Logger.getLogger(Test.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

}
