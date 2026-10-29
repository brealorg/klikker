package no.enaasen.tvremote.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.google.protobuf.InvalidProtocolBufferException
import java.io.InputStream
import java.io.OutputStream
import no.enaasen.tvremote.proto.userdata.UserData

object UserDataSerializer : Serializer<UserData> {
    override val defaultValue: UserData = UserData.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): UserData = try {
        UserData.parseFrom(input)
    } catch (e: InvalidProtocolBufferException) {
        throw CorruptionException("Cannot read user data", e)
    }

    override suspend fun writeTo(t: UserData, output: OutputStream) = t.writeTo(output)
}
