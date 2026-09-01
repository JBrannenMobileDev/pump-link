package dev.pumplink.protocol

@JvmInline
value class Flags(val bits: Int) {
    val auth: Boolean get() = bits and AUTH != 0
    val ackReq: Boolean get() = bits and ACK_REQ != 0
    val resp: Boolean get() = bits and RESP != 0

    fun with(auth: Boolean = this.auth, ackReq: Boolean = this.ackReq, resp: Boolean = this.resp): Flags {
        var next = 0
        if (auth) next = next or AUTH
        if (ackReq) next = next or ACK_REQ
        if (resp) next = next or RESP
        return Flags(next)
    }

    companion object {
        const val AUTH = 0x08
        const val ACK_REQ = 0x04
        const val RESP = 0x02

        val NONE = Flags(0)

        fun of(auth: Boolean = false, ackReq: Boolean = false, resp: Boolean = false): Flags =
            NONE.with(auth = auth, ackReq = ackReq, resp = resp)
    }
}
